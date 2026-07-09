# Load provided Fourier-series files for 5_2 and 6_1 knots, build three-knot layout at 120°,
# compute induced velocity field (Biot–Savart with Rosenhead–Moore core) in the z=0 plane,
# and locate candidate interaction "capture" points as in-plane stagnation points (|v| minima)
# along the lines connecting the three axes.
#
# Outputs:
#  - PNG figure of geometry + speed contours + stagnation points
#  - Printed coordinates of the three stagnation points
#  - CSV of the stagnation points
#
# Notes:
#  - We align each knot's central axis with global z and place axes at radius R around origin,
#    at angles 0°, 120°, 240°.
#  - Circulation quantum kappa from user's constants.
#  - Regularization core a_core = r_c.
#

import numpy as np
import matplotlib.pyplot as plt
import pandas as pd
from ace_tools_open import display_dataframe_to_user
import matplotlib

matplotlib.use('TkAgg')

# ---------- Constants from earlier ----------
C_e = 1_093_845.63          # m/s
r_c = 1.40897017e-15        # m
kappa = 2*np.pi*C_e*r_c     # m^2/s
a_core = r_c

# ---------- Parse fseries files ----------
def load_fseries(path):
    rows = []
    with open(path, 'r') as f:
        for line in f:
            line=line.strip()
            if not line or line.startswith('%') or line.startswith('#'):
                continue
            parts = line.split()
            if len(parts) < 6:
                continue
            try:
                ax, bx, ay, by, az, bz = map(float, parts[:6])
                rows.append((ax,bx,ay,by,az,bz))
            except:
                pass
    arr = np.array(rows)
    ax = arr[:,0]; bx=arr[:,1]; ay=arr[:,2]; by=arr[:,3]; az=arr[:,4]; bz=arr[:,5]
    return ax, bx, ay, by, az, bz

ax52,bx52,ay52,by52,az52,bz52 = load_fseries(
    './knot.5_2.fseries')
ax61,bx61,ay61,by61,az61,bz61 = load_fseries(
    './knot.6_1.fseries')

def fourier_curve(t, ax, bx, ay, by, az, bz):
    x = np.zeros_like(t, dtype=float)
    y = np.zeros_like(t, dtype=float)
    z = np.zeros_like(t, dtype=float)
    # harmonics indexed by 1..N
    N = len(ax)
    for j in range(1, N+1):
        x += ax[j-1]*np.cos(j*t) + bx[j-1]*np.sin(j*t)
        y += ay[j-1]*np.cos(j*t) + by[j-1]*np.sin(j*t)
        z += az[j-1]*np.cos(j*t) + bz[j-1]*np.sin(j*t)
    return x, y, z

# Sample curves
t = np.linspace(0, 2*np.pi, 2400, endpoint=False)
x52,y52,z52 = fourier_curve(t, ax52,bx52,ay52,by52,az52,bz52)
x61,y61,z61 = fourier_curve(t, ax61,bx61,ay61,by61,az61,bz61)

# Center each curve around origin roughly
def center_curve(x,y,z):
    return x - np.mean(x), y - np.mean(y), z - np.mean(z)
x52,y52,z52 = center_curve(x52,y52,z52)
x61,y61,z61 = center_curve(x61,y61,z61)

# Scale to similar envelope sizes (optional mild normalization)
def radius_rms(x,y):
    return np.sqrt(np.mean(x*x + y*y))
s52 = radius_rms(x52,y52)
s61 = radius_rms(x61,y61)
target = 0.6  # target in meters for visual clarity
x52 *= target/s52; y52 *= target/s52; z52 *= target/s52
x61 *= target/s61; y61 *= target/s61; z61 *= target/s61

# Build three copies: rotate triangle so knot1 lies at x=0 (angle 90°)
R_axes = 2.0
phi_deg = -30.0  # rotation offset to make center[1] = (0, R_axes)
base_deg = np.array([0.0, 120.0, 240.0]) + phi_deg
angles = np.deg2rad(base_deg)
centers = np.column_stack([R_axes*np.cos(angles), R_axes*np.sin(angles)])


# Rotation to align each knot's "major plane" with its local xy; here we just translate,
# assuming the fseries curves are already roughly centered and oriented.
# (Advanced: could perform a PCA to align; not necessary for field study.)

# Assemble global coordinates
X_list = []
DL_list = []  # segment vectors for Biot–Savart
Gamma_list = []

def segments_from_curve(x,y,z):
    # create segments by finite differences (closed)
    X = np.stack([x,y,z], axis=1)
    Xn = np.roll(X, -1, axis=0)
    dl = Xn - X
    return X, dl

# u (5_2) at angle 0
xL = x52 + centers[0,0]; yL = y52 + centers[0,1]; zL = z52
XL, dLL = segments_from_curve(xL,yL,zL)
X_list.append(XL); DL_list.append(dLL); Gamma_list.append(kappa)

# d (6_1) at angle 120
xC = x61 + centers[1,0]; yC = y61 + centers[1,1]; zC = z61
XC, dLC = segments_from_curve(xC,yC,zC)
X_list.append(XC); DL_list.append(dLC); Gamma_list.append(kappa)

# u (5_2) at angle 240
xR = x52 + centers[2,0]; yR = y52 + centers[2,1]; zR = z52
XR, dLR = segments_from_curve(xR,yR,zR)
X_list.append(XR); DL_list.append(dLR); Gamma_list.append(kappa)


def velocity_from_filaments(P):
    v = np.zeros((P.shape[0],3), dtype=float)
    for Xs, dls, Gam in zip(X_list, DL_list, Gamma_list):
        const = Gam/(4*np.pi)
        for i in range(Xs.shape[0]):
            R = P - Xs[i]              # (M,3)
            cross = np.cross(dls[i], R) # (M,3)
            r2 = np.sum(R*R, axis=1) + a_core*a_core  # (M,)
            denom = (r2**1.5)[:,None]  # (M,1)
            v += const * (cross / denom)
    return v

# Recompute grid and speed
grid_R = 3.5
n = 160
xs = np.linspace(-grid_R, grid_R, n)
ys = np.linspace(-grid_R, grid_R, n)
XX,YY = np.meshgrid(xs, ys)
ZZ = np.zeros_like(XX)
Pts = np.stack([XX.ravel(), YY.ravel(), ZZ.ravel()], axis=1)

V = velocity_from_filaments(Pts)
speed = np.linalg.norm(V, axis=1).reshape(XX.shape)

def stagnation_on_segment(P1, P2, num=400):
    ts = np.linspace(0,1,num)
    pts = (1-ts)[:,None]*P1 + ts[:,None]*P2
    v = velocity_from_filaments(np.column_stack([pts, np.zeros(num)]))
    sp = np.linalg.norm(v, axis=1)
    k = np.argmin(sp)
    return pts[k], sp[k], v[k]

axis_pts = np.column_stack([centers, np.zeros(3)])
pairs = [(0,1),(1,2),(2,0)]
stag_pts = []
for i,j in pairs:
    P1 = centers[i]; P2 = centers[j]
    pmin, smin, vmin = stagnation_on_segment(P1, P2)
    stag_pts.append({"pair":f"{i}-{j}","x":pmin[0],"y":pmin[1],"speed_min":smin,"vx":vmin[0],"vy":vmin[1]})

# Plot
fig = plt.figure(figsize=(9,8))
ax = fig.add_subplot(111)
eps = 1e-30
cs = ax.contourf(XX, YY, np.log10(speed+eps), levels=30, cmap='viridis')
cbar = plt.colorbar(cs, ax=ax, label='log10 |v| (m/s)')

for c in centers:
    ax.plot([c[0], c[0]], [c[1]-0.1, c[1]+0.1], 'w-', lw=3)
    circ = plt.Circle((c[0], c[1]), 0.08, color='w', fill=False, lw=1.5)
    ax.add_patch(circ)

ax.plot(xL, yL, '-', color='#2ca02c', lw=1.5)
ax.plot(xC, yC, '-', color='#1f77b4', lw=1.5)
ax.plot(xR, yR, '-', color='#2ca02c', lw=1.5)

for sp in stag_pts:
    ax.plot(sp["x"], sp["y"], 'ro', ms=6)
    ax.text(sp["x"], sp["y"]+0.12, f"{sp['pair']}", color='w', ha='center', va='bottom')

ax.plot([centers[0,0], centers[1,0], centers[2,0], centers[0,0]],
        [centers[0,1], centers[1,1], centers[2,1], centers[0,1]], 'w--', lw=1.2)

ax.set_aspect('equal', 'box')
ax.set_xlim(-grid_R, grid_R); ax.set_ylim(-grid_R, grid_R)
ax.set_xlabel('x (m)'); ax.set_ylabel('y (m)')
ax.set_title('Three-knot layout at 120° (z=0): speed field and stagnation points')

out_png = "sst_three_knot_speed_stagnation.png"
plt.tight_layout()
plt.savefig(out_png, dpi=200, bbox_inches='tight')

df = pd.DataFrame(stag_pts)
csv_path = "sst_three_knot_stagnation_points.csv"
df.to_csv(csv_path, index=False)

print(df)
# Apply per-knot in-plane rotations (about local z) and recompute field & stagnation points.
# Requested rotations (viewed from +z):
#  knot 0 (u,5_2 at angle 0°): 90° CW  -> -90 deg
#  knot 1 (d,6_1 at angle 120°): 45° CCW -> +45 deg
#  knot 2 (u,5_2 at angle 240°): 135° CCW -> +135 deg


def rotz(x, y, theta_rad):
    ct = np.cos(theta_rad); st = np.sin(theta_rad)
    xr =  ct*x - st*y
    yr =  st*x + ct*y
    return xr, yr

# Angles in radians
ang0 = np.deg2rad(-90.0)
ang1 = np.deg2rad(+30.0)
ang2 = np.deg2rad(+150.0)

# Rotate locally, then translate to centers
x0r, y0r = rotz(x52, y52, ang0)
x1r, y1r = rotz(x61, y61, ang1)
x2r, y2r = rotz(x52, y52, ang2)

xL, yL, zL = x0r + centers[0,0], y0r + centers[0,1], z52
xC, yC, zC = x1r + centers[1,0], y1r + centers[1,1], z61
xR, yR, zR = x2r + centers[2,0], y2r + centers[2,1], z52

def segments_from_curve(x,y,z):
    X = np.stack([x,y,z], axis=1)
    Xn = np.roll(X, -1, axis=0)
    dl = Xn - X
    return X, dl

XL, dLL = segments_from_curve(xL,yL,zL)
XC, dLC = segments_from_curve(xC,yC,zC)
XR, dLR = segments_from_curve(xR,yR,zR)

X_list = [XL, XC, XR]
DL_list = [dLL, dLC, dLR]
Gamma_list = [kappa, kappa, kappa]



def velocity_from_filaments(P):
    v = np.zeros((P.shape[0],3), dtype=float)
    for Xs, dls, Gam in zip(X_list, DL_list, Gamma_list):
        const = Gam/(4*np.pi)
        for i in range(Xs.shape[0]):
            R = P - Xs[i]
            cross = np.cross(dls[i], R)
            r2 = np.sum(R*R, axis=1) + a_core*a_core
            denom = (r2**1.5)[:,None]
            v += const * (cross / denom)
    return v

grid_R = 3.5
n = 160
xs = np.linspace(-grid_R, grid_R, n)
ys = np.linspace(-grid_R, grid_R, n)
XX,YY = np.meshgrid(xs, ys)
Pts = np.stack([XX.ravel(), YY.ravel(), np.zeros_like(XX).ravel()], axis=1)
V = velocity_from_filaments(Pts)
speed = np.linalg.norm(V, axis=1).reshape(XX.shape)

def stagnation_on_segment(P1, P2, num=600):
    ts = np.linspace(0,1,num)
    pts = (1-ts)[:,None]*P1 + ts[:,None]*P2
    v = velocity_from_filaments(np.column_stack([pts, np.zeros(num)]))
    sp = np.linalg.norm(v, axis=1)
    k = np.argmin(sp)
    return pts[k], sp[k], v[k]

pairs = [(0,1),(1,2),(2,0)]
stag_pts = []
for i,j in pairs:
    P1 = centers[i]; P2 = centers[j]
    pmin, smin, vmin = stagnation_on_segment(P1, P2)
    stag_pts.append({"pair":f"{i}-{j}","x":pmin[0],"y":pmin[1],"speed_min":smin,"vx":vmin[0],"vy":vmin[1]})

fig = plt.figure(figsize=(9,8))
ax = fig.add_subplot(111)
eps = 1e-30
cs = ax.contourf(XX, YY, np.log10(speed+eps), levels=30, cmap='viridis')
cbar = plt.colorbar(cs, ax=ax, label='log10 |v| (m/s)')

for c in centers:
    ax.plot([c[0], c[0]], [c[1]-0.1, c[1]+0.1], 'w-', lw=3)
    circ = plt.Circle((c[0], c[1]), 0.08, color='w', fill=False, lw=1.5)
    ax.add_patch(circ)

ax.plot(xL, yL, '-', color='#2ca02c', lw=1.5, label='u (5_2) rot')
ax.plot(xC, yC, '-', color='#1f77b4', lw=1.5, label='d (6_1) rot')
ax.plot(xR, yR, '-', color='#2ca02c', lw=1.5)

for sp in stag_pts:
    ax.plot(sp["x"], sp["y"], 'ro', ms=6)
    ax.text(sp["x"], sp["y"]+0.12, f"{sp['pair']}", color='w', ha='center', va='bottom')

ax.plot([centers[0,0], centers[1,0], centers[2,0], centers[0,0]],
        [centers[0,1], centers[1,1], centers[2,1], centers[0,1]], 'w--', lw=1.2)

ax.set_aspect('equal', 'box')
ax.set_xlim(-grid_R, grid_R); ax.set_ylim(-grid_R, grid_R)
ax.set_xlabel('x (m)'); ax.set_ylabel('y (m)')
ax.set_title('Rotated knots at 120° (z=0): speed field and stagnation points')

out_png = "sst_three_knot_rotated_speed_stagnation.png"
plt.tight_layout()
plt.savefig(out_png, dpi=200, bbox_inches='tight')

df = pd.DataFrame(stag_pts)
csv_path = "sst_three_knot_rotated_stagnation_points.csv"
df.to_csv(csv_path, index=False)


# Apply per-knot in-plane rotations (about local z) and recompute field & stagnation points.
# Requested rotations (viewed from +z):
#  knot 0 (u,5_2 at angle 0°): 90° CW  -> -90 deg
#  knot 1 (d,6_1 at angle 120°): 45° CCW -> +45 deg
#  knot 2 (u,5_2 at angle 240°): 135° CCW -> +135 deg


def rotz(x, y, theta_rad):
    ct = np.cos(theta_rad); st = np.sin(theta_rad)
    xr =  ct*x - st*y
    yr =  st*x + ct*y
    return xr, yr

# Angles in radians
ang0 = np.deg2rad(+60.0)
ang1 = np.deg2rad(+180.0)
ang2 = np.deg2rad(-60.0)

# Rotate locally, then translate to centers
x0r, y0r = rotz(x52, y52, ang0)
x1r, y1r = rotz(x61, y61, ang1)
x2r, y2r = rotz(x52, y52, ang2)

xL, yL, zL = x0r + centers[0,0], y0r + centers[0,1], z52
xC, yC, zC = x1r + centers[1,0], y1r + centers[1,1], z61
xR, yR, zR = x2r + centers[2,0], y2r + centers[2,1], z52

def segments_from_curve(x,y,z):
    X = np.stack([x,y,z], axis=1)
    Xn = np.roll(X, -1, axis=0)
    dl = Xn - X
    return X, dl

XL, dLL = segments_from_curve(xL,yL,zL)
XC, dLC = segments_from_curve(xC,yC,zC)
XR, dLR = segments_from_curve(xR,yR,zR)

X_list = [XL, XC, XR]
DL_list = [dLL, dLC, dLR]
Gamma_list = [kappa, kappa, kappa]



def velocity_from_filaments(P):
    v = np.zeros((P.shape[0],3), dtype=float)
    for Xs, dls, Gam in zip(X_list, DL_list, Gamma_list):
        const = Gam/(4*np.pi)
        for i in range(Xs.shape[0]):
            R = P - Xs[i]
            cross = np.cross(dls[i], R)
            r2 = np.sum(R*R, axis=1) + a_core*a_core
            denom = (r2**1.5)[:,None]
            v += const * (cross / denom)
    return v

grid_R = 3.5
n = 160
xs = np.linspace(-grid_R, grid_R, n)
ys = np.linspace(-grid_R, grid_R, n)
XX,YY = np.meshgrid(xs, ys)
Pts = np.stack([XX.ravel(), YY.ravel(), np.zeros_like(XX).ravel()], axis=1)
V = velocity_from_filaments(Pts)
speed = np.linalg.norm(V, axis=1).reshape(XX.shape)

def stagnation_on_segment(P1, P2, num=600):
    ts = np.linspace(0,1,num)
    pts = (1-ts)[:,None]*P1 + ts[:,None]*P2
    v = velocity_from_filaments(np.column_stack([pts, np.zeros(num)]))
    sp = np.linalg.norm(v, axis=1)
    k = np.argmin(sp)
    return pts[k], sp[k], v[k]

pairs = [(0,1),(1,2),(2,0)]
stag_pts = []
for i,j in pairs:
    P1 = centers[i]; P2 = centers[j]
    pmin, smin, vmin = stagnation_on_segment(P1, P2)
    stag_pts.append({"pair":f"{i}-{j}","x":pmin[0],"y":pmin[1],"speed_min":smin,"vx":vmin[0],"vy":vmin[1]})

fig = plt.figure(figsize=(9,8))
ax = fig.add_subplot(111)
eps = 1e-30
cs = ax.contourf(XX, YY, np.log10(speed+eps), levels=30, cmap='viridis')
cbar = plt.colorbar(cs, ax=ax, label='log10 |v| (m/s)')

for c in centers:
    ax.plot([c[0], c[0]], [c[1]-0.1, c[1]+0.1], 'w-', lw=3)
    circ = plt.Circle((c[0], c[1]), 0.08, color='w', fill=False, lw=1.5)
    ax.add_patch(circ)

ax.plot(xL, yL, '-', color='#2ca02c', lw=1.5, label='u (5_2) rot')
ax.plot(xC, yC, '-', color='#1f77b4', lw=1.5, label='d (6_1) rot')
ax.plot(xR, yR, '-', color='#2ca02c', lw=1.5)

for sp in stag_pts:
    ax.plot(sp["x"], sp["y"], 'ro', ms=6)
    ax.text(sp["x"], sp["y"]+0.12, f"{sp['pair']}", color='w', ha='center', va='bottom')

ax.plot([centers[0,0], centers[1,0], centers[2,0], centers[0,0]],
        [centers[0,1], centers[1,1], centers[2,1], centers[0,1]], 'w--', lw=1.2)

ax.set_aspect('equal', 'box')
ax.set_xlim(-grid_R, grid_R); ax.set_ylim(-grid_R, grid_R)
ax.set_xlabel('x (m)'); ax.set_ylabel('y (m)')
ax.set_title('Knots tops aiming at center° (z=0): speed field and stagnation points')

out_png = "sst_three_knot_180_speed_stagnation.png"
plt.tight_layout()
plt.savefig(out_png, dpi=200, bbox_inches='tight')

df = pd.DataFrame(stag_pts)
csv_path = "sst_three_knot_180_stagnation_points.csv"
df.to_csv(csv_path, index=False)

# plt.show()



