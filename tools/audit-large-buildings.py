"""Read-only source / saved-chunk audit; output goes to stdout."""
from pathlib import Path
import io, struct, gzip, zlib, math, collections, sys

def nbt(data):
    f=io.BytesIO(data)
    def read(fmt): return struct.unpack('>'+fmt,f.read(struct.calcsize('>'+fmt)))[0]
    def string(): return f.read(read('H')).decode('utf-8')
    def value(t):
        if t in range(1,7): return read({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
        if t==7: return f.read(read('i'))
        if t==8: return string()
        if t==9:
            ty=read('B'); return [value(ty) for _ in range(read('i'))]
        if t==10:
            d={}
            while (ty:=read('B')):
                key=string();d[key]=value(ty)
            return d
        if t in (11,12): return [read('i' if t==11 else 'q') for _ in range(read('i'))]
        raise ValueError(t)
    t=read('B');string();return value(t)

def chunks(directory):
    for p in directory.glob('r.*.mca'):
        data=p.read_bytes()
        for i in range(1024):
            entry=int.from_bytes(data[4*i:4*i+4],'big');off=(entry>>8)*4096
            if not off: continue
            length=int.from_bytes(data[off:off+4],'big');ty=data[off+4]
            if ty&128: continue
            raw=data[off+5:off+4+length]
            try: yield nbt(zlib.decompress(raw) if ty==2 else gzip.decompress(raw) if ty==1 else raw)
            except Exception as e: print('Unreadable',p,i,e)

def grid(ch):
    out={}
    for s in ch.get('sections',[]):
        if not 4<=int(s['Y'])<=6: continue
        if 'block_states' not in s: continue
        b=s['block_states'];pal=[p['Name'] for p in b['palette']]
        bits=max(4,(len(pal)-1).bit_length());per=64//bits;data=b.get('data',[])
        vals=[pal[((data[i//per]&((1<<64)-1))>>((i%per)*bits))&((1<<bits)-1)] if data else pal[0] for i in range(4096)]
        out[int(s['Y'])]=vals
    return out

def at(g,x,y,z):
    section=g.get(y//16)
    return section[((y%16)*16+z)*16+x] if section else 'minecraft:air'

root=Path(sys.argv[1]);save=Path(sys.argv[2]);world={}
for c in chunks(save/'region'): world[(c['xPos'],c['zPos'])]=grid(c)
print('Loaded',len(world),'saved chunks')
for name in ['CAF工业超市','大学']:
    p=next(root.rglob(name+'.nbt'));d=nbt(gzip.decompress(p.read_bytes()))
    pal=[r['Name'] for r in d['palette']];blocks={tuple(b['pos']):pal[b['state']] for b in d['blocks'] if pal[b['state']] not in {'minecraft:air','minecraft:cave_air','minecraft:void_air','minecraft:structure_void'}}
    lo=[min(pos[i] for pos in blocks) for i in range(3)];hi=[max(pos[i] for pos in blocks) for i in range(3)]
    w=(hi[0]-lo[0]+16)//16;dep=(hi[2]-lo[2]+16)//16;pad=[(w*16-hi[0]+lo[0]-1)//2,(dep*16-hi[2]+lo[2]-1)//2]
    local={(x-lo[0]+pad[0],y-lo[1],z-lo[2]+pad[1]):b for (x,y,z),b in blocks.items()}
    cells=collections.defaultdict(list)
    allcells=collections.defaultdict(collections.Counter)
    for (x,y,z),b in local.items():
        allcells[(x//16,z//16)][b]+=1
        if b.startswith('minecraft:') and y>0: cells[(x//16,z//16)].append((x%16,y,z%16,b))
    votes=collections.Counter()
    for (cx,cz),g in world.items():
        for (sx,sz),samples in cells.items():
            samples=samples[::max(1,len(samples)//40)][:40]
            if len(samples)<15: continue
            matches=sum(at(g,x,71+y,z)==b for x,y,z,b in samples)
            if matches>=len(samples)*.92: votes[(cx-sx,cz-sz)]+=1
    print(name,'source size',d['size'],'bounds',lo,hi,'footprint',w,dep,'anchors',votes.most_common(5))
    for coord in allcells:
        if not cells[coord]: print('No vanilla upper blocks',coord,'source',allcells[coord].most_common(8))
    for (ax,az),count in votes.most_common(2):
        if count<4:continue
        print('anchor',ax,az,'matching cells',count)
        for sz in range(dep):
            row=[]
            for sx in range(w):
                g=world.get((ax+sx,az+sz));samples=cells[(sx,sz)]
                if g is None:row.append(' ---');continue
                n=sum(at(g,x,71+y,z)==b for x,y,z,b in samples)
                row.append(f'{round(100*n/max(1,len(samples))):4}')
            print(' '.join(row))
