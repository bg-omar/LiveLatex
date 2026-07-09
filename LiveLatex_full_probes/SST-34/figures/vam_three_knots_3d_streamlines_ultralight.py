#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VAM three-knot 3D streamlines (ultralight) — colored by speed
Rotations: knot0=-90°, knot1=+30°, knot2=+150°
Requires the files:
  - knot.5_2.fseries
  - knot.6_1.fseries
in the same directory as this script, or edit the paths below.
"""

import os
import numpy as np
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d.art3d import Poly3DCollection, Line3DCollection
import matplotlib
matplotlib.use('TkAgg')

# ---------------------- Config ----------------------
# Output path
OUT_PNG = "sst_three_knot_3d_streamlines_colored_ULTRALIGHT.png"

# Knot Fourier series file paths (adjust if needed)
F_52 = './knot.5_2.fseries'
F_61 = './knot.6_1.fseries'

# Rotations in degrees (viewed from +z)
ANG0_DEG = -90.0   # knot0: u(5_2) at +x axis
ANG1_DEG = +30.0   # knot1: d(6_1) at 120°
ANG2_DEG = +150.0  # knot2: u(5_2) at 240°

# Axis ring radius
R_AXES = 2.0

# Downsampling stride for filaments (bigger -> fewer segments)
SEG_STRIDE = 15    # ~80 segments/knot at 2400 pts

# Streamline integration parameters (ultralight)
STEP_LEN = 0.10
NSTEPS   = 250
SEEDS_PER_KNOT = 10  # set to 2 or more to increase density

# ------------------ Utility functions ------------------
def load_fseries(path):
    rows = []
    with open(path, 'r') as f:
        for line in f:
            s=line.strip()
            if not s or s.startswith('%') or s.startswith('#'):
                continue
            parts = s.split()
            if len(parts) < 6: 
                continue
            ax, bx, ay, by, az, bz = map(float, parts[:6])
            rows.append((ax,bx,ay,by,az,bz))
    arr = np.array(rows, dtype=float)
    return arr[:,0],arr[:,1],arr[:,2],arr[:,3],arr[:,4],arr[:,5]

def fourier_curve(t, ax, bx, ay, by, az, bz):
    x = np.zeros_like(t, dtype=float); y = np.zeros_like(t, dtype=float); z = np.zeros_like(t, dtype=float)
    N = len(ax)
    for j in range(1, N+1):
        x += ax[j-1]*np.cos(j*t) + bx[j-1]*np.sin(j*t)
        y += ay[j-1]*np.cos(j*t) + by[j-1]*np.sin(j*t)
        z += az[j-1]*np.cos(j*t) + bz[j-1]*np.sin(j*t)
    return x, y, z

def center_and_scale(x,y,z, target=0.6):
    x = x - np.mean(x); y = y - np.mean(y); z = z - np.mean(z)
    s = np.sqrt(np.mean(x*x + y*y))
    if s > 0: x*=target/s; y*=target/s; z*=target/s
    return x,y,z

def rotz(x, y, theta_rad):
    ct = np.cos(theta_rad); st = np.sin(theta_rad)
    return ct*x - st*y, st*x + ct*y

def downsample_segments(x,y,z, stride=30):
    P = np.stack([x,y,z], axis=1)[::stride]
    Q = np.roll(P, -1, axis=0)
    dL = Q - P
    return P, dL

# ------------------ Physical parameters ------------------
# VAM constants you provided
C_e = 1_093_845.63                # m/s (vortex tangential velocity scale)
r_c = 1.40897017e-15              # m (core radius)
kappa = 2*np.pi*C_e*r_c           # m^2/s (circulation)
a_core = r_c                      # m (regularization length)

# ------------------ Main computation ------------------
def build_geometry():
    # Load Fourier series for u(5_2) and d(6_1)
    ax52,bx52,ay52,by52,az52,bz52 = load_fseries(F_52)
    ax61,bx61,ay61,by61,az61,bz61 = load_fseries(F_61)

    # High-res parametric curves
    t = np.linspace(0, 2*np.pi, 2400, endpoint=False)
    x52,y52,z52 = fourier_curve(t, ax52,bx52,ay52,by52,az52,bz52)
    x61,y61,z61 = fourier_curve(t, ax61,bx61,ay61,by61,az61,bz61)

    # Normalize/center
    x52,y52,z52 = center_and_scale(x52,y52,z52, target=0.6)
    x61,y61,z61 = center_and_scale(x61,y61,z61, target=0.6)

    # Centers (equilateral triangle in xy)
    centers = np.column_stack([R_AXES*np.cos(np.deg2rad([0,120,240])),
                               R_AXES*np.sin(np.deg2rad([0,120,240]))])

    # Rotations and placements
    ang0 = np.deg2rad(ANG0_DEG)
    ang1 = np.deg2rad(ANG1_DEG)
    ang2 = np.deg2rad(ANG2_DEG)

    x0r,y0r = rotz(x52,y52,ang0)  # u(5_2)
    x1r,y1r = rotz(x61,y61,ang1)  # d(6_1)
    x2r,y2r = rotz(x52,y52,ang2)  # u(5_2)

    xK0,yK0,zK0 = x0r + centers[0,0], y0r + centers[0,1], z52
    xK1,yK1,zK1 = x1r + centers[1,0], y1r + centers[1,1], z61
    xK2,yK2,zK2 = x2r + centers[2,0], y2r + centers[2,1], z52

    return (xK0,yK0,zK0), (xK1,yK1,zK1), (xK2,yK2,zK2), centers

def vectorized_velocity_builder(P_all, dL_all):
    # Returns a function v(P) that computes velocity at points P (M,3)
    def vfield(P):
        P = np.atleast_2d(P)                # (M,3)
        R = P[:,None,:] - P_all[None,:,:]   # (M,N,3)
        cross = np.cross(dL_all[None,:,:], R)   # (M,N,3)
        r2 = np.sum(R*R, axis=2) + a_core*a_core
        v = (kappa/(4*np.pi)) * np.sum(cross / (r2**1.5)[:,:,None], axis=1)
        return v
    return vfield

def integrate_streamline(vfield, seed, step_len=0.10, nsteps=50, both=True):
    pts=[seed.copy()]; p=seed.copy()
    for _ in range(nsteps):
        v=vfield(p)[0]; s=np.linalg.norm(v)+1e-30
        p=p+(v/s)*step_len
        if not (-4<=p[0]<=4 and -4<=p[1]<=4 and -3<=p[2]<=3): break
        pts.append(p.copy())
    if both:
        p=seed.copy(); back=[]
        for _ in range(nsteps):
            v=vfield(p)[0]; s=np.linalg.norm(v)+1e-30
            p=p-(v/s)*step_len
            if not (-4<=p[0]<=4 and -4<=p[1]<=4 and -3<=p[2]<=3): break
            back.append(p.copy())
        pts=back[::-1]+pts
    pts=np.array(pts)
    if len(pts)>=2:
        mids = 0.5*(pts[:-1]+pts[1:])
        speeds = np.linalg.norm(vfield(mids), axis=1)
    else:
        speeds = np.array([])
    return pts, speeds

def main():
    # Build knots and centers
    (xK0,yK0,zK0), (xK1,yK1,zK1), (xK2,yK2,zK2), centers = build_geometry()

    # Downsample segments
    def ds(x,y,z): return downsample_segments(x,y,z, stride=SEG_STRIDE)
    P0,dL0 = ds(xK0,yK0,zK0); P1,dL1 = ds(xK1,yK1,zK1); P2,dL2 = ds(xK2,yK2,zK2)

    # Vectorized field
    P_all = np.vstack([P0,P1,P2])
    dL_all = np.vstack([dL0,dL1,dL2])
    vfield = vectorized_velocity_builder(P_all, dL_all)

    # Seeds (SEEDS_PER_KNOT around each axis at z=0)
    seeds=[]
    seed_r=0.45
    phis = np.linspace(0, 2*np.pi, SEEDS_PER_KNOT, endpoint=False)
    for (cx,cy) in centers:
        for ang in phis:
            seeds.append(np.array([cx+seed_r*np.cos(ang), cy+seed_r*np.sin(ang), 0.0]))

    # Integrate streamlines
    stream_segs=[]
    for s in seeds:
        pts, spd = integrate_streamline(vfield, s, step_len=STEP_LEN, nsteps=NSTEPS)
        if len(pts) >= 2:
            segs = np.stack([pts[:-1], pts[1:]], axis=1)
            stream_segs.append((segs, spd))

    # ---------- Plot ----------
    fig = plt.figure(figsize=(10,9))
    ax = fig.add_subplot(111, projection='3d')

    ax.plot(xK0, yK0, zK0, linewidth=2.0, label='u (5$_2$)')
    ax.plot(xK1, yK1, zK1, linewidth=2.0, label='d (6$_1$)')
    ax.plot(xK2, yK2, zK2, linewidth=2.0, label='u (5$_2$)')

    # Swirl lines
    for cx, cy in centers:
        ax.plot([cx, cx], [cy, cy], [-3.6, 3.6], linestyle='--', linewidth=3)

    # Colored streamlines
    lc_last=None
    for segs, spd in stream_segs:
        lc = Line3DCollection(segs, cmap='viridis')
        if len(spd)==0: continue
        lc.set_array(spd)
        lc.set_linewidth(2.0)
        ax.add_collection(lc)
        lc_last = lc

    # z=0 plane (faint)
    def z0_plane(xmin, xmax, ymin, ymax, z=0.0, alpha=0.06):
        verts = [[xmin,ymin,z],[xmax,ymin,z],[xmax,ymax,z],[xmin,ymax,z]]
        return Poly3DCollection([verts], alpha=alpha, facecolor='gray', edgecolor='none')
    ax.add_collection3d(z0_plane(-3.8, 3.8, -3.8, 3.8, 0.0, alpha=0.05))

    # Limits & view
    allx = np.concatenate([xK0,xK1,xK2])
    ally = np.concatenate([yK0,yK1,yK2])
    allz = np.concatenate([zK0,zK1,zK2])
    for segs,_ in stream_segs:
        allx = np.concatenate([allx, segs[:,:,0].ravel()])
        ally = np.concatenate([ally, segs[:,:,1].ravel()])
        allz = np.concatenate([allz, segs[:,:,2].ravel()])
    max_range = np.array([allx.max()-allx.min(), ally.max()-ally.min(), allz.max()-allz.min()]).max()
    Xb = 0.55*max_range
    cxm, cym, czm = np.mean(allx), np.mean(ally), np.mean(allz)
    ax.set_xlim(cxm-Xb, cxm+Xb); ax.set_ylim(cym-Xb, cym+Xb); ax.set_zlim(czm-Xb, czm+Xb)

    ax.set_xlabel('x (m)'); ax.set_ylabel('y (m)'); ax.set_zlabel('z (m)')
    ax.view_init(elev=26, azim=-45)
    ax.legend(loc='upper left')

    if lc_last is not None:
        cb = plt.colorbar(lc_last, ax=ax, pad=0.02, fraction=0.03)
        cb.set_label('|v| (m/s)')

    plt.tight_layout()
    plt.savefig(OUT_PNG, dpi=190, bbox_inches='tight')
    print(f"Saved figure to: {os.path.abspath(OUT_PNG)}")
    plt.show()

if __name__ == "__main__":
    main()
