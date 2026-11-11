"""Détecte les contours des rochers à partir du MNT LiDAR HD de l'IGN (0,5 m).

Usage : python rochers.py <id_secteur Boolder> <dossier de sortie>
"""
import io
import json
import os
import sqlite3
import sys
import urllib.parse
import urllib.request

import numpy as np
import rasterio
from rasterio.features import shapes
from rasterio.transform import from_bounds
from pyproj import Transformer
from scipy import ndimage
from shapely.geometry import shape, mapping
from shapely.ops import transform as shp_transform

AREA = int(sys.argv[1])
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
RES = 0.5
MARGE = 60  # mètres autour du secteur

db = sqlite3.connect(os.path.join(os.path.dirname(__file__), '..', 'donnees', 'boolder.db'))
name, s, w, n, e = db.execute(
    'select name,south_west_lat,south_west_lon,north_east_lat,north_east_lon from areas where id=?', (AREA,)).fetchone()
pts = db.execute('select latitude,longitude from problems where area_id=?', (AREA,)).fetchall()

to93 = Transformer.from_crs('EPSG:4326', 'EPSG:2154', always_xy=True)
to84 = Transformer.from_crs('EPSG:2154', 'EPSG:4326', always_xy=True)
x0, y0 = to93.transform(w, s)
x1, y1 = to93.transform(e, n)
x0, y0, x1, y1 = [round(v / RES) * RES for v in (x0 - MARGE, y0 - MARGE, x1 + MARGE, y1 + MARGE)]
W, H = int((x1 - x0) / RES), int((y1 - y0) / RES)
print(name, 'emprise', x0, y0, x1, y1, W, 'x', H, 'pixels', len(pts), 'blocs')

params = {
    'SERVICE': 'WMS', 'VERSION': '1.3.0', 'REQUEST': 'GetMap',
    'LAYERS': 'IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.LAMB93', 'STYLES': '',
    'CRS': 'EPSG:2154', 'BBOX': f'{x0},{y0},{x1},{y1}', 'WIDTH': W, 'HEIGHT': H, 'FORMAT': 'image/tiff',
}
url = 'https://data.geopf.fr/wms-r/wms?' + urllib.parse.urlencode(params)
print(url)
req = urllib.request.Request(url, headers={'User-Agent': 'CarnetDeGres/1.0'})
raw = urllib.request.urlopen(req, timeout=120).read()
print('reçu', len(raw), 'octets', raw[:4])
with rasterio.MemoryFile(raw) as mf, mf.open() as src:
    z = src.read(1).astype('float64')
    print('raster', src.dtypes, src.nodata, z.shape)
bad = (z < -1000) | (z > 5000) | ~np.isfinite(z)
print('altitudes', np.nanmin(z[~bad]), np.nanmax(z[~bad]), 'pixels invalides', int(bad.sum()))
if bad.any():
    z[bad] = np.median(z[~bad])

tr = from_bounds(x0, y0, x1, y1, W, H)
np.save(os.path.join(OUT, 'mnt.npy'), z.astype('float32'))


def disque(r_m):
    r = int(r_m / RES)
    yy, xx = np.mgrid[-r:r + 1, -r:r + 1]
    return xx * xx + yy * yy <= r * r


def detecte(seuil, rayon):
    # Sol lissé = ouverture morphologique : enlève tout ce qui est plus étroit que le disque
    sol = ndimage.grey_opening(z, footprint=disque(rayon))
    sol = ndimage.uniform_filter(sol, size=5)
    h = z - sol
    m = h > seuil
    m = ndimage.binary_opening(m, structure=disque(0.6))
    m = ndimage.binary_fill_holes(m)
    lab, nb = ndimage.label(m)
    tailles = ndimage.sum(m, lab, range(1, nb + 1))
    garde = np.isin(lab, 1 + np.nonzero(tailles * RES * RES >= 3)[0])
    return garde, h


resultats = {}
for seuil, rayon in [(0.8, 8), (1.2, 8), (1.2, 12)]:
    m, h = detecte(seuil, rayon)
    polys = []
    for geom, v in shapes(m.astype('uint8'), mask=m, transform=tr):
        p = shape(geom).simplify(0.35, preserve_topology=True)
        if p.area < 3:
            continue
        hmax = float(h[rasterio.features.geometry_mask([geom], m.shape, tr, invert=True)].max())
        g = shp_transform(lambda x, y, zz=None: to84.transform(x, y), p)
        polys.append({'type': 'Feature', 'properties': {'surface_m2': round(p.area, 1), 'hauteur_max_m': round(hmax, 1)},
                      'geometry': mapping(g)})
    cle = f's{seuil}_r{rayon}'
    json.dump({'type': 'FeatureCollection', 'features': polys},
              open(os.path.join(OUT, f'rochers_{cle}.geojson'), 'w'))
    resultats[cle] = (m, h, polys)
    print(cle, len(polys), 'rochers')

# Image de contrôle
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LightSource

px = [to93.transform(lon, lat) for lat, lon in pts]
cols = [((x - x0) / RES) for x, y in px]
rows = [((y1 - y) / RES) for x, y in px]
ombre = LightSource(azdeg=315, altdeg=40).hillshade(z, vert_exag=2, dx=RES, dy=RES)
fig, axes = plt.subplots(1, 3, figsize=(30, 30 * H / W / 3 + 1.2), dpi=110)
for ax, (cle, (m, h, polys)) in zip(axes, resultats.items()):
    ax.imshow(ombre, cmap='gray')
    ax.contour(m, levels=[0.5], colors='#ffb000', linewidths=1.6)
    ax.scatter(cols, rows, s=9, c='#e0218a', zorder=3)
    dedans = sum(1 for c, r in zip(cols, rows) if 0 <= int(r) < H and 0 <= int(c) < W and
                 ndimage.binary_dilation(m, iterations=4)[int(r), int(c)])
    ax.set_title(f'{name} — {cle} : {len(polys)} rochers, {dedans}/{len(pts)} blocs à moins de 2 m d\'un contour', fontsize=13)
    ax.set_axis_off()
plt.tight_layout()
plt.savefig(os.path.join(OUT, 'controle.png'))

# Zoom sur la partie la plus dense
c0, r0 = int(np.median(cols)), int(np.median(rows))
win = 160
fig, axes = plt.subplots(1, 3, figsize=(30, 10.5), dpi=110)
for ax, (cle, (m, h, polys)) in zip(axes, resultats.items()):
    sl = (slice(max(0, r0 - win), r0 + win), slice(max(0, c0 - win), c0 + win))
    ax.imshow(ombre[sl], cmap='gray')
    ax.contour(m[sl], levels=[0.5], colors='#ffb000', linewidths=2)
    ax.scatter([c - max(0, c0 - win) for c in cols], [r - max(0, r0 - win) for r in rows], s=22, c='#e0218a', zorder=3)
    ax.set_xlim(0, 2 * win); ax.set_ylim(2 * win, 0)
    ax.set_title(f'Zoom — {cle}', fontsize=14)
    ax.set_axis_off()
plt.tight_layout()
plt.savefig(os.path.join(OUT, 'zoom.png'))
print('fini')
