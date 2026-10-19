#!/usr/bin/env python3
"""Compile the production lyric clients on a host JVM; live calls are explicit opt-in."""
import argparse, concurrent.futures, hashlib, json, os, pathlib, subprocess, urllib.parse, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
COORDS = [
 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.10',
 'org.jetbrains.kotlin:kotlin-stdlib:2.2.10',
 'org.jetbrains.kotlin:kotlin-script-runtime:2.2.10',
 'org.jetbrains.kotlin:kotlin-reflect:1.6.10',
 'org.jetbrains.kotlin:kotlin-daemon-embeddable:2.2.10',
 'org.jetbrains.intellij.deps:trove4j:1.0.20200330',
 'org.jetbrains:annotations:13.0',
 'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0',
 'com.squareup.okhttp3:okhttp:4.12.0', 'com.squareup.okio:okio-jvm:3.6.0',
 'org.json:json:20240303', 'org.jsoup:jsoup:1.18.3',
 'junit:junit:4.13.2', 'org.hamcrest:hamcrest-core:1.3',
]
CLIENT_FILES = ['HttpClient', 'SyncedLyricsQuality', 'LyricsMatching', 'ArtistAliases', 'LyricsSearch',
 'QQMusicClient', 'NeteaseClient', 'LrclibClient', 'QqMusicSearch',
 'QqMusicSearchMetadata', 'QqMusicRequestSigner', 'QqMusicQrcDecryptor',
 'QrcPayloadParser', 'LrcParser', 'TimedLyricsMerger', 'CachedLyricsResult',
 'LyricsRace', 'LyricsCacheResolver', 'LyricsPlaybackSession', 'InFlightLyricsRequests',
 'LyricsRequestGeneration']
TEST_FILES = ['ArtistAliasesTest', 'SyncedLyricsQualityTest', 'LyricsMatchingTest', 'LyricsSearchTest',
 'QqMusicSearchMetadataTest', 'QqMusicRequestSignerTest', 'QqMusicSearchTest',
 'QqMusicQrcDecryptorTest', 'QrcPayloadParserTest', 'LrcParserTest', 'TimedLyricsMergerTest',
 'LyricsRaceTest', 'LyricsCacheResolverTest', 'InFlightLyricsRequestsTest', 'HttpClientTest']

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--dataset', type=pathlib.Path, default=HERE/'hot-100.json')
 p.add_argument('--output', type=pathlib.Path, default=ROOT/'build/live-search/results.jsonl')
 p.add_argument('--sources', default='netease,qq,lrclib')
 p.add_argument('--limit', type=int, default=100)
 p.add_argument('--workers', type=int, default=3)
 p.add_argument('--source-spacing-ms', type=int, default=3000)
 p.add_argument('--unit-only', action='store_true')
 a=p.parse_args()
 if not 1 <= a.workers <= 8: p.error('--workers must be between 1 and 8')
 if a.source_spacing_ms < 0: p.error('--source-spacing-ms must be nonnegative')
 cache=pathlib.Path(os.environ.get('LYRICS_LIVE_DEPS', ROOT/'build/live-search/deps'))
 cache.mkdir(parents=True,exist_ok=True)
 def download(coord):
  group,artifact,version=coord.split(':'); name=f'{artifact}-{version}.jar'; target=cache/name
  if not target.exists():
   temporary=target.with_suffix('.tmp')
   urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/{group.replace(".","/")}/{artifact}/{version}/{name}',temporary)
   temporary.replace(target)
  return str(target)
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: jars=list(pool.map(download,COORDS))
 cp=os.pathsep.join(jars); classes=ROOT/'build/live-search/classes'; classes.mkdir(parents=True,exist_ok=True)
 package=ROOT/'app/src/main/java/com/lyricsplus/android'
 sources=[package/'lyrics'/f'{n}.kt' for n in CLIENT_FILES]
 sources += [package/'data'/'LyricsLine.kt',package/'data'/'NowPlaying.kt',HERE/'LiveSearch.kt']
 sources += list((HERE/'shims').glob('*.kt'))
 test_package=ROOT/'app/src/test/java/com/lyricsplus/android/lyrics'
 tests=[n for n in TEST_FILES if (test_package/f'{n}.kt').exists()]
 sources += [test_package/f'{n}.kt' for n in tests]
 subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),*map(str,sources)],check=True,cwd=ROOT)
 runtime=os.pathsep.join([str(classes),cp])
 subprocess.run(['java','-cp',runtime,'org.junit.runner.JUnitCore',
  *[f'com.lyricsplus.android.lyrics.{n}' for n in tests]],check=True,cwd=ROOT)
 if a.unit_only:return
 fingerprint=hashlib.sha256()
 for source in sorted(sources):
  fingerprint.update(str(source.relative_to(ROOT)).encode()); fingerprint.update(source.read_bytes())
 fingerprint.update(a.dataset.read_bytes())
 manifest_path=a.output.with_suffix('.manifest.json')
 manifest=dict(fingerprint=fingerprint.hexdigest(),dataset=str(a.dataset.resolve().relative_to(ROOT))
  if a.dataset.resolve().is_relative_to(ROOT) else a.dataset.name,
  dependencies=COORDS,sources=a.sources.split(','),limit=a.limit,workers=a.workers,sourceSpacingMs=a.source_spacing_ms,
  baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
  deadlineMs=10000,androidDiskCache=False)
 if a.output.exists() and a.output.stat().st_size:
  previous=json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
  if previous.get('fingerprint') != manifest['fingerprint']:
   raise SystemExit('Source or dataset changed: use a new output path for a fresh run.')
 a.output.parent.mkdir(parents=True,exist_ok=True)
 manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 options=[]
 proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
 if proxy:
  parsed=urllib.parse.urlsplit(proxy)
  if parsed.username or parsed.password: raise SystemExit('Authenticated proxy requires explicit JVM configuration; credentials will not be printed.')
  options += [f'-Dhttps.proxyHost={parsed.hostname}',f'-Dhttps.proxyPort={parsed.port or 80}']
 subprocess.run(['java',*options,'-cp',runtime,'com.lyricsplus.android.lyrics.LiveSearchKt',
  str(a.dataset.resolve()),str(a.output.resolve()),a.sources,str(a.limit),str(a.workers),str(a.source_spacing_ms)],check=True,cwd=ROOT)
if __name__=='__main__':main()
