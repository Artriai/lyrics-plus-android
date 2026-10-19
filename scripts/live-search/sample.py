#!/usr/bin/env python3
"""Freeze a deduplicated 40 CN / 30 JP / 30 US Apple Music chart sample."""
import argparse, datetime, json, pathlib, urllib.request
HERE=pathlib.Path(__file__).resolve().parent

def build():
 songs=[];charts=[];seen=set()
 for country,count in [('cn',40),('jp',30),('us',30)]:
  url=f'https://rss.marketingtools.apple.com/api/v2/{country}/music/most-played/100/songs.json'
  with urllib.request.urlopen(url,timeout=60) as r: feed=json.load(r)['feed']
  charts.append(dict(country=country,url=url,updated=feed['updated'],sampleCount=count))
  picked=[]
  for rank,item in enumerate(feed['results'],1):
   key=(item['name'].casefold(),item['artistName'].casefold())
   if key in seen:continue
   seen.add(key)
   picked.append(dict(sampleId=f'{country}-{rank:03}',country=country,rank=rank,
    appleId=item['id'],title=item['name'],artist=item['artistName'],url=item['url'],album='',durationSeconds=0))
   if len(picked)==count:break
  # Lookup enriches actual metadata rather than guessing durations from another recording.
  lookup='https://itunes.apple.com/lookup?country='+country+'&entity=song&id='+','.join(s['appleId'] for s in picked)
  try:
   with urllib.request.urlopen(lookup,timeout=60) as r: records=json.load(r)['results']
   byid={str(r.get('trackId')):r for r in records if r.get('wrapperType')=='track'}
   for s in picked:
    record=byid.get(s['appleId'],{})
    s['album']=record.get('collectionName','')
    s['durationSeconds']=round(record.get('trackTimeMillis',0)/1000)
  except Exception as e: print(f'{country}: lookup unavailable ({type(e).__name__}); unknown album/duration retained')
  songs.extend(picked)
 assert len(songs)==100
 return dict(retrievedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),charts=charts,songs=songs)
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=pathlib.Path,default=HERE/'hot-100.json');a=p.parse_args()
 a.output.write_text(json.dumps(build(),ensure_ascii=False,indent=2)+'\n')
 print(a.output)
