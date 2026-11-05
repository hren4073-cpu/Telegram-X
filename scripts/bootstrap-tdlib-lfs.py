#!/usr/bin/env python3
"""Restore exact upstream LFS objects. Never regenerate hashes or commit binary dependencies."""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TDLIB = ROOT / 'tdlib'
PIN = 'b47cd822037a4c38916f0b78f57298f10a88821e'

def git(*args):
    return subprocess.check_output(['git', '-C', str(TDLIB), *args], text=True).strip()

def main():
    if git('rev-parse', 'HEAD') != PIN:
        raise SystemExit('TDLib must remain at the pinned revision ' + PIN)
    subprocess.run(['git','-C',str(TDLIB),'lfs','install','--local','--skip-repo'],check=True)
    fetched = subprocess.run(['git', '-C', str(TDLIB), 'lfs', 'fetch', 'origin', PIN]).returncode == 0
    if not fetched:
        print('LFS batch failed; retrieving public upstream objects through media.githubusercontent.com with original SHA-256 verification.', flush=True)
        # Respect custom storage by refusing to guess where its object store resides.
        storage = subprocess.run(['git','-C',str(TDLIB),'config','--get','lfs.storage'],capture_output=True,text=True)
        if storage.returncode == 0:
            raise SystemExit('Custom lfs.storage configured: resolve normal LFS authentication instead of writing to an unknown store.')
        cache = Path(git('rev-parse', '--absolute-git-dir')) / 'lfs' / 'objects'
        files = git('lfs','ls-files','-n').splitlines()
        objects = {}
        for name in files:
            pointer = git('show', f'{PIN}:{name}')
            match = re.fullmatch(r'version https://git-lfs.github.com/spec/v1\noid sha256:([0-9a-f]{64})\nsize ([0-9]+)',pointer)
            if not match:
                raise SystemExit(f'Unexpected pointer format: {name}')
            oid, size = match.groups()
            objects.setdefault(oid,(name,int(size)))
        def verified(path, oid, size):
            return path.is_file() and path.stat().st_size == size and hashlib.file_digest(path.open('rb'),'sha256').hexdigest() == oid
        def download(item):
            oid,(name,size) = item
            dest = cache / oid[:2] / oid[2:4] / oid
            if verified(dest,oid,size): return
            dest.parent.mkdir(parents=True,exist_ok=True)
            tmp = dest.with_suffix('.download')
            url = 'https://media.githubusercontent.com/media/TGX-Android/tdlib/' + PIN + '/' + urllib.parse.quote(name,safe='/')
            try:
                with urllib.request.urlopen(url,timeout=90) as response, tmp.open('wb') as output:
                    while data := response.read(1024*1024): output.write(data)
                if not verified(tmp,oid,size):
                    raise RuntimeError(f'Upstream object size or SHA-256 mismatch: {name}')
                os.replace(tmp,dest)
                print(f'Verified {name}',flush=True)
            finally:
                tmp.unlink(missing_ok=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(download,objects.items()))
    subprocess.run(['git','-C',str(TDLIB),'lfs','fsck','--objects',PIN],check=True)
    subprocess.run(['git','-C',str(TDLIB),'lfs','checkout'],check=True)
    for name in git('lfs','ls-files','-n').splitlines():
        pointer = git('show',f'{PIN}:{name}')
        fields = dict(line.split(' ',1) for line in pointer.splitlines())
        path = TDLIB / name
        oid = fields['oid'].removeprefix('sha256:')
        if path.stat().st_size != int(fields['size']) or hashlib.file_digest(path.open('rb'),'sha256').hexdigest() != oid:
            raise SystemExit(f'LFS checkout did not restore the pinned object: {name}')
    print('TDLib LFS objects restored and verified against pinned pointers, including every working-tree file.')

if __name__ == '__main__':
    main()
