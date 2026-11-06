"""Exclusive, host-local identity state. This does not persist pending events."""
from __future__ import annotations
import json
import os
from pathlib import Path
import tempfile
import time

U32 = 2**32 - 1

def atomic_json(path, value):
    path = Path(path)
    if path.is_symlink(): raise ValueError('Unsafe state path')
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name+'.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            json.dump(value, stream, ensure_ascii=False, sort_keys=True, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        # Windows readers/antivirus may briefly deny replacement of an open snapshot.
        # Keep the old complete file and retry for a bounded 200ms; never truncate it.
        for attempt in range(21):
            try:
                os.replace(temporary, path)
                break
            except PermissionError:
                if attempt == 20: raise
                time.sleep(.01)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)

class FileLock:
    def __init__(self, path): self.path = Path(path); self.stream = None
    def __enter__(self):
        if self.path.is_symlink(): raise ValueError('Unsafe lock path')
        self.stream = self.path.open('a+b')
        self.stream.seek(0, 2)
        if self.stream.tell() == 0: self.stream.write(b'0'); self.stream.flush()
        self.stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.stream.close(); self.stream = None
            raise ValueError('State already owned') from None
        return self
    def __exit__(self, *_):
        if self.stream is not None:
            self.stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.stream.fileno(), fcntl.LOCK_UN)
            self.stream.close(); self.stream = None

class IdentityState:
    def __init__(self, directory, node):
        self.path = Path(directory) / (node+'.json')
        self.node = node
        self.value = json.loads(self.path.read_text(encoding='utf-8'))
        if (self.value.get('node_id') != node or type(self.value.get('boot_id')) is not int
            or not 0 <= self.value['boot_id'] <= U32
            or type(self.value.get('sentry_revision')) is not int
            or not 0 <= self.value['sentry_revision'] <= 2**64-1):
            raise ValueError('Invalid identity state')
    def reserve(self):
        if self.value['boot_id'] == U32: raise ValueError('Identity exhausted')
        new = {**self.value, 'boot_id': self.value['boot_id'] + 1}
        atomic_json(self.path, new)
        self.value = new
        return new['boot_id']
    def revision(self, revision):
        if revision <= self.value['sentry_revision'] or revision > 2**64-1:
            raise ValueError('Stale revision')
        new = {**self.value, 'sentry_revision': revision}
        atomic_json(self.path, new)
        self.value = new
