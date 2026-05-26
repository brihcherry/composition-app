# System Composition — User Guide

This application visualizes the Military Health System (MHS) IT portfolio and answers the question: **"How does the composition of this system change over time?"** Select any military health IT system to explore a network of connected systems and data interfaces (ICDs), and track how that network evolves through modernization — seeing which legacy interfaces are being retired, which new ones are being built, and what stage of development each interface is currently in.

---

## Getting Started

When the application loads, you will see a top navigation bar and the **Composition Over Time** page. All data is fetched live from the SEMOSS backend.

### Navigation Bar

| Link | Page |
|---|---|
| **Composition Over Time** | Main visualization (default) |
| **Debug Comparison** | Developer QA tool — compares legacy vs. new data |
| **SPARQL Inspector** | Developer tool — run direct database queries |

---

## Composition Over Time Page

This is the primary page. It lets you select a military health IT system and visualize how its interfaces change during migration.

### Step 1 — Select a System

Use the **dropdown in the page header** to choose a system (e.g., "MHS GENESIS", "AERO"). Once selected, the graph and table will populate with data for that system.

The status line below the dropdown shows the current load state and, once loaded, displays the total number of **nodes** and **edges** in the graph.

---

### The Interface Modernization Status Table (Left Panel)

The left panel shows a table of all interfaces (ICDs) for the selected system. You can **resize this panel** by dragging its right edge.

| Column | Description |
|---|---|
| **ICD** | Interface name |
| **Phase** | Color-coded SDLC phase badge (visible in Transition mode only) |
| **LOE** | Level of Effort — a numerical estimate of work required |
| **GL Tag** | General Ledger tag classification |

The table sorting changes based on the current Map Mode:
- **Transition mode**: Sorted by phase (Completed → Deploy → Test → Develop → Design → Requirements → Decommissioned → Sustainment), then alphabetically
- **Other modes**: Alphabetically

---

### The Network Graph (Center)

The center panel shows a **force-directed graph** where:
- **Nodes** are IT systems, data interfaces (ICDs), and data domains
- **Edges** (arrows) show how data flows between systems through interfaces

You can:
- **Zoom in/out** with your mouse scroll wheel
- **Pan** by clicking and dragging the background
- **Move individual nodes** by clicking and dragging them
- **Hover over a node or edge** to see a detailed tooltip

#### Node Types and Colors

| Node Type | Color | Description |
|---|---|---|
| **System** | Blue | An IT system (e.g., MHS GENESIS, AERO) |
| **SystemInterface (ICD)** | Purple | A data interface between two systems |
| **DataObject** | Orange | A data category (e.g., Vital Signs, Patient Demographics) |

#### Tooltip Details

**When hovering a node**, you will see:
- Label and type
- Full system name (if available)
- Description (truncated to 200 characters)
- Connection count

**When hovering an edge**, you will see:
- Source → Target system names
- Data payload type
- Format, protocol, and frequency
- Interface name

---

### Map Mode Controls (Right Panel)

The right panel contains controls that change what is displayed in the graph.

#### Label Display

- **Hide labels** — removes all node labels from the graph for a cleaner view
- **Hide interface labels** — hides only the ICD node labels (system and data object labels remain)

---

#### Initial Mode

**What it does:** Shows the "as-is" baseline — the legacy network before any migration work began. New LOE-tracked interfaces are hidden because they do not yet exist in this view.

**How to use:** Click the **Initial** toggle button.

**What you see:** Only sustainment and to-be-decommissioned interfaces are visible. Nodes with no remaining connections after this filter are automatically hidden.

---

#### Transition Mode

**What it does:** Shows all systems and interfaces simultaneously with each interface color-coded by its current SDLC phase, letting you track migration progress at any point in time.

**How to use:** Click the **Transition** toggle button. A timeline slider and scrollable interface list appear below the mode buttons.

**What you see:**
- All nodes are visible. ICD nodes are colored by SDLC phase based on the current slider position.
- When an interface's work is "Completed," its replaced legacy interfaces turn **red (Decommissioned)**.
- A status label shows "Not started," "In progress," or "Migration complete."
- A small **info box** in the bottom-left of the graph shows the running count of **Interfaces Added** and **Interfaces Decommissioned**.

**LOE Slider:** Drag to scrub through the migration timeline. The range is based on total **Level of Effort (LOE)** units across all modernized interfaces. A scrollable list beneath the slider shows all new interfaces with their current phase badge and color.

**SDLC Phase Color Key:**

| Phase | Color |
|---|---|
| Requirements | Light yellow-green |
| Design | Light green |
| Develop | Medium green |
| Test | Medium-dark green |
| Deploy | Dark green |
| Completed | Deep green |
| Decommissioned | Red |

---

#### Final Mode

**What it does:** Shows the "to-be" end state — the modernized network after migration is complete. Interfaces that will be decommissioned are hidden.

**How to use:** Click the **Final** toggle button.

**What you see:** Only sustainment interfaces and completed new interfaces remain visible. Decommissioned interfaces and any nodes with no remaining connections are automatically hidden.

---

## Understanding the Data

### What Is an ICD?

An **ICD (Interface Control Document)** is a formalized data interface between two IT systems. In the graph, ICDs appear as purple **SystemInterface** nodes connecting two system nodes.

### What Is LOE?

**Level of Effort (LOE)** is a numerical measure of the work required to build or decommission an interface. Each phase of the SDLC (Requirements, Design, Develop, Test, Deploy) contributes an LOE value. The sum of all phases represents the total effort for an interface.

### Three Types of Interfaces

| Type | Description |
|---|---|
| **LOE-tracked (New)** | New interfaces being built as part of modernization. Colored by SDLC phase in Transition mode. |
| **Decommissioned** | Legacy interfaces being retired. Shown in red when their replacing interface completes. |
| **Sustainment** | Existing interfaces that are not changing. Shown with no special color indicator. |

### Map Mode Filtering Logic

| Mode | Nodes Shown | Purpose |
|---|---|---|
| Initial | Non-new nodes (sustainment + decommissioned-only) | "As-is" baseline |
| Transition | All nodes | Full migration view |
| Final | Non-decommissioned nodes (sustainment + completed new) | "To-be" end state |

After filtering by mode, any **stranded nodes** (nodes with no remaining edges) are automatically hidden — except for the selected system, which always remains as the graph anchor.

---

## Debug & Developer Pages

These pages are intended for developers and data analysts validating the backend data.

### Debug Comparison (`/#/debug-comparison`)

Compares the output of the legacy SEMOSS data system against the new reactor for every active system. Useful for validating that data migration is accurate.

**What you can do:**
- Click **Run All** to compare all systems sequentially
- Use the **Filter** tabs (All / Failures / Mismatches) to focus on problem areas
- Use the **Search** box to find a specific system by name
- Click **Export JSON** to download a full comparison report as a timestamped file
- Click **Cancel** to stop a running comparison

### SPARQL Inspector (`/#/debug-sparql`)

A direct query tool for the backend RDF databases.

**Section 1 — LOE Overlay Inspector:**
- Select a system and click **Inspect** to see detailed diagnostics about the LOE phase overlay computation
- Shows counts like `scopedICDCount`, `rawRowCount`, and highlights any **ambiguous groups** (cases where multiple data sources conflict for the same interface/phase)

**Section 2 — Ad-hoc SPARQL Query Runner:**
- Select a target database (TAP_Core_Data, FutureDB, or FutureCostDB)
- Enter or edit a SPARQL SELECT query
- Set a row limit and click **Run Query**
- Results display in a table with shortened URIs for readability
