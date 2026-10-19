#!/usr/bin/env python3
"""Summarize completed live results without treating timeouts as missing catalog entries."""
import argparse, collections, csv, json, pathlib, statistics

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=pathlib.Path);p.add_argument('--csv',type=pathlib.Path);a=p.parse_args()
 rows=[json.loads(l) for l in a.input.read_text().splitlines() if l.strip()]
 for source in ['netease','qq','lrclib']:
  group=[x for x in rows if x['source']==source]; times=[x['elapsedMs'] for x in group]
  print(source,len(group),dict(collections.Counter(x['status'] for x in group)),
   'medianMs',round(statistics.median(times)) if times else None)
 if a.csv:
  a.csv.parent.mkdir(parents=True,exist_ok=True)
  with a.csv.open('w',newline='') as f:
   columns=['sampleId','title','artist','source','status','elapsedMs','matchScore','selectionScore','lineCount','selectedId','selectedTitle','selectedArtist','error']
   w=csv.DictWriter(f,columns,lineterminator="\n");w.writeheader()
   for r in rows:
    record={k:r.get(k) for k in columns};s=r.get('selected') or {}
    record.update(selectedId=s.get('id'),selectedTitle=s.get('title'),selectedArtist=s.get('artist'));w.writerow(record)
if __name__=='__main__':main()
