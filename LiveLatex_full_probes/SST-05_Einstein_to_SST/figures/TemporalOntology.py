# Cleaned and SST-compliant figure generation (no explicit colors; single-plot per figure)
# Produces: /mnt/data/temporal_spiral_v1.png, /mnt/data/temporal_spiral_v2.png, /mnt/data/kairos_phase.png

import numpy as np
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

# ---------- Figure 1: Phase spiral of layered time (Swirl–String) ----------

# Spiral parameters (widened)
theta = np.linspace(0, 3.5 * np.pi, 500)
r = np.linspace(0.4, 1.5, 500)  # widened spiral radius
z = np.linspace(0, 1.5, 500)
x = r * np.cos(theta)
y = r * np.sin(theta)

# Time layer labels and fractions (outer to inner)
time_labels = [
    (1.45 / 1.5, r"Topological Time ($\mathbb{K}$)"),
    (1.20 / 1.5, r"Circulation Duration ($T_v$)"),
    (0.95 / 1.5, r"Swirl Clock ($S^{\circlearrowleft}_{(t)}$)"),
    (0.65 / 1.5, r"Proper Time ($\tau$)"),
    (0.45 / 1.5, r"Now-Point ($\nu_0$)"),
]

# Build 3D figure (single plot)
fig1 = plt.figure(figsize=(8, 6))
ax1 = fig1.add_subplot(111, projection='3d')

# Spiral curve (no explicit colors)
ax1.plot(x, y, z, lw=2)

# Labels with rounded boxes (no explicit colors)
for zi_frac, label in time_labels:
    idx = min(int(zi_frac * len(x)), len(x) - 1)
    ax1.text(
        x[idx] + 0.3, y[idx], z[idx] + 0.02, label, fontsize=9,
        bbox=dict(facecolor='white', edgecolor='black', boxstyle='round,pad=0.3')
    )

# Absolute foliation label at base (no explicit colors)
ax1.text(
    0.5, 0, 0, r"Absolute Time ($\mathcal{N}$)", fontsize=10, color="darkblue",
    bbox=dict(facecolor='white', edgecolor='navy', boxstyle='round,pad=0.3')
)

ax1.set_title(r"Phase Spiral of Layered Time (Swirl–String)" "\n"
              r"$\mathcal{N} \rightarrow \nu_0 \rightarrow \tau \rightarrow S^{\circlearrowleft}_{(t)} \rightarrow T_v \rightarrow \mathbb{K}$",
              fontsize=12)
ax1.set_axis_off()
ax1.view_init(elev=-155, azim=175)

# Save v1
f1_path_v1 = "temporal_spiral_v1.png"
plt.savefig(f1_path_v1, dpi=150)

# Alternate view (top-down)
ax1.view_init(elev=80, azim=75)
f1_path_v2 = "TemporalOntology.png"
plt.savefig(f1_path_v2, dpi=150)
plt.close(fig1)

# ---------- Figure 2: Swirl-clock phase with a topological update (Kairos) ----------

# Time axis
t = np.linspace(0, 10, 1000)
omega_base = 2 * np.pi / 5

# Base phase evolution
theta_phase = np.sin(omega_base * t) + 0.3 * np.sin(3 * omega_base * t)

# Kairos window (center + width)
kairos_time = 7.0
kairos_width = 0.6
kairos_start = kairos_time - kairos_width / 2
kairos_end = kairos_time + kairos_width / 2

# Disruption: transient oscillation + persistent phase shift
disruption = np.exp(-((t - kairos_time) ** 2) / (2 * (kairos_width / 4) ** 2))
theta_phase += 0.7 * disruption * np.sin(10 * omega_base * t)
theta_phase += 1.0 * (t > kairos_end)  # permanent upward phase step

# Single-axes figure
fig2, ax2 = plt.subplots(figsize=(10, 4))

# Phase curve (no explicit color)
ax2.plot(t, theta_phase, label=r'Swirl-clock phase $\theta(t)$')

# Highlight Kairos window (no explicit color; alpha only)
kairos_patch = Rectangle(
    (kairos_start, -2),
    kairos_width,
    5,  # height to cover y-range [-2, 3]
    linewidth=0,
    alpha=0.25
)
ax2.add_patch(kairos_patch)

# Annotation
kairos_index = np.abs(t - kairos_time).argmin()
ax2.annotate("Topological Update\n(Kairos event)",
             xy=(kairos_time, theta_phase[kairos_index]),
             xytext=(kairos_time - 3.0, 1.4),
             arrowprops=dict(arrowstyle="->"),
             fontsize=11,
             ha='right')

# Labels and styling (no explicit colors)
ax2.set_title("Swirl-clock Phase with a Topological Update", fontsize=14)
ax2.set_xlabel(r"Absolute Time $\mathcal{N}$", fontsize=12)
ax2.set_ylabel(r"Phase $\theta(t)$", fontsize=12)
ax2.grid(True)
ax2.set_ylim(-2, 3)
ax2.legend()

plt.tight_layout()
f2_path = "TemporalOntologyKairosMoment.png"
plt.savefig(f2_path, dpi=150)
plt.close(fig2)