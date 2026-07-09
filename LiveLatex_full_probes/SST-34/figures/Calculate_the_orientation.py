# Evaluate induced velocity at the three swirl axes under different setups:
# A) current: (u5_2, d6_1, u5_2), rotations (-90°, +30°, +150°)
# B) mirror the middle knot (flip chirality)
# C) rotate all three by +180° (about their own z axes)
#
# We'll report the velocity at each axis due to the OTHER two knots,
# i.e., v_i = sum_j≠i v_j( axis_i ).

import numpy as np
import pandas as pd

# ---------- helpers (compact) ----------
def load_fseries(path):
    rows = []
    with open(path, 'r') as f:
        for line in f:
            s=line.strip()
            if not s or s.startswith('%') or s.startswith('#'):
                continue
            parts = s.split()
            if len(parts) < 6: continue
            try:
                ax, bx, ay, by, az, bz = map(float, parts[:6])
                rows.append((ax,bx,ay,by,az,bz))
            except: pass
    arr = np.array(rows)
    return arr[:,0],arr[:,1],arr[:,2],arr[:,3],arr[:,4],arr[:,5]

def fourier_curve(t, ax, bx, ay, by, az, bz):
    x = np.zeros_like(t); y = np.zeros_like(t); z = np.zeros_like(t)
    N = len(ax)
    for j in range(1, N+1):
        x += ax[j-1]*np.cos(j*t) + bx[j-1]*np.sin(j*t)
        y += ay[j-1]*np.cos(j*t) + by[j-1]*np.sin(j*t)
        z += az[j-1]*np.cos(j*t) + bz[j-1]*np.sin(j*t)
    return x,y,z

def center_and_scale(x,y,z, target=0.6):
    x = x-np.mean(x); y=y-np.mean(y); z=z-np.mean(z)
    s = np.sqrt(np.mean(x*x + y*y))
    if s>0: x*=target/s; y*=target/s; z*=target/s
    return x,y,z

def rotz(x,y,theta):
    ct=np.cos(theta); st=np.sin(theta)
    return ct*x - st*y, st*x + ct*y

def segments(x,y,z, stride=6):
    P = np.stack([x,y,z], axis=1)[::stride]
    Q = np.roll(P, -1, axis=0)
    dL = Q - P
    return P, dL

# Biot–Savart (regularized)
C_e = 1_093_845.63; r_c = 1.40897017e-15
kappa = 2*np.pi*C_e*r_c; a_core = r_c

def velocity_from_segments(P, Pseg, dLseg, Gam):
    P = np.atleast_2d(P)                 # (M,3)
    R = P[:,None,:] - Pseg[None,:,:]     # (M,N,3)
    cross = np.cross(dLseg[None,:,:], R) # (M,N,3)
    r2 = np.sum(R*R, axis=2) + a_core*a_core
    v = (Gam/(4*np.pi)) * np.sum(cross/(r2**1.5)[:,:,None], axis=1) # (M,3)
    return v

# ---------- load curves ----------
ax52,bx52,ay52,by52,az52,bz52 = load_fseries(
    './knot.5_2.fseries')
ax61,bx61,ay61,by61,az61,bz61 = load_fseries(
    './knot.6_1.fseries')

t = np.linspace(0,2*np.pi,2400,endpoint=False)
x52,y52,z52 = fourier_curve(t, ax52,bx52,ay52,by52,az52,bz52)
x61,y61,z61 = fourier_curve(t, ax61,bx61,ay61,by61,az61,bz61)
x52,y52,z52 = center_and_scale(x52,y52,z52, 0.6)
x61,y61,z61 = center_and_scale(x61,y61,z61, 0.6)

# centers
R_axes=2.0
centers = np.column_stack([R_axes*np.cos(np.deg2rad([0,120,240])),
                           R_axes*np.sin(np.deg2rad([0,120,240])),
                           np.zeros(3)])

# helper to build a knot with rotation angle and (optional) mirror
def build_knot(kind, angle_deg, center_xy, mirror=False):
    if kind=='u':  # 5_2
        x,y,z = x52.copy(), y52.copy(), z52.copy()
    else:          # 'd' -> 6_1
        x,y,z = x61.copy(), y61.copy(), z61.copy()
    # mirror: flip chirality via (x,y,z)->(x,-y,z)
    if mirror:
        y = -y
    xr, yr = rotz(x,y,np.deg2rad(angle_deg))
    cx, cy = center_xy
    return xr+cx, yr+cy, z

# assemble scenarios
# A) current: (u,-90), (d,+30), (u,+150), no mirror
A_knots = [
    ('u',-90.0, centers[0,:2], False),
    ('d',+30.0, centers[1,:2], False),
    ('u',+150.0, centers[2,:2], False),
]
# B) mirror the middle knot
B_knots = [
    ('u',-90.0, centers[0,:2], False),
    ('d',+30.0, centers[1,:2], True),
    ('u',+150.0, centers[2,:2], False),
]
# C) rotate all by +180°
C_knots = [
    ('u',-90.0+180.0, centers[0,:2], False),
    ('d',+30.0+180.0, centers[1,:2], False),
    ('u',+150.0+180.0, centers[2,:2], False),
]

def axes_velocities(knots):
    segs=[]; kinds=[]
    for kind,ang,ctr,mir in knots:
        x,y,z = build_knot(kind, ang, ctr, mir)
        Pseg, dLseg = segments(x,y,z, stride=8)
        segs.append((Pseg,dLseg))
        kinds.append(kind)
    # velocities at axes from "other" filaments
    v_at = []
    for i in range(3):
        p = centers[i]
        v = np.zeros(3)
        for j in range(3):
            if j==i: continue
            Pseg,dLseg = segs[j]
            v += velocity_from_segments(p, Pseg, dLseg, kappa)[0]
        v_at.append(v)
    return np.array(v_at)

VA = axes_velocities(A_knots)
VB = axes_velocities(B_knots)
VC = axes_velocities(C_knots)

df = pd.DataFrame({
    'scenario':['A']*3 + ['B']*3 + ['C']*3,
    'axis_idx':[0,1,2]*3,
    'vx (m/s)': np.r_[VA[:,0], VB[:,0], VC[:,0]],
    'vy (m/s)': np.r_[VA[:,1], VB[:,1], VC[:,1]],
    'vz (m/s)': np.r_[VA[:,2], VB[:,2], VC[:,2]],
    '|v| (m/s)': np.r_[np.linalg.norm(VA,axis=1),
                        np.linalg.norm(VB,axis=1),
                        np.linalg.norm(VC,axis=1)]
})
print(df)