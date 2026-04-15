// CompositionTimelinePage.tsx — Full-screen page for insight #21:
// "How does the composition of this system change over time?"
//
// Layout: left table | center graph | right sidebar
// Three map modes: Initial (legacy), Transition (migration in progress), Final (new system)
//
// Transition logic matches the legacy AngularJS network-timeline:
//   - LOE phases ordered: Requirements → Design → Develop → Test → Deploy
//   - Cumulative startLOE / totalLOE computed client-side per ICD
//   - LOE slider determines which phase is active and colors nodes/edges accordingly
//   - When an ICD crosses "Completed", its dependICDS (legacy ICDs) turn red (decommissioned)
//   - Phase colors match legacy CSS exactly

import { useMemo, useState, useCallback, useRef, useEffect } from "react";
import { runPixel } from "@semoss/sdk";
import { useInsight } from "@semoss/sdk/react";
import { TimelineGraph } from "@/components/TimelineGraph";
import { GraphTooltip } from "@/components/GraphTooltip";
import { GraphLegend } from "@/components/GraphLegend";
import { getTimeGraphData } from "@/lib/timeGraphData";
import type { TooltipData } from "@/types/graph";
import type { RawTimeGraphData, TimeNode } from "@/types/timeGraph";

interface SystemOption {
	uri: string;
	label: string;
}

type MapMode = "initial" | "transition" | "final";

const MODE_LABELS: Record<MapMode, string> = {
	initial: "Initial",
	transition: "Transition",
	final: "Final",
};

const MODE_DESCRIPTIONS: Record<MapMode, string> = {
	initial: "Legacy network before migration",
	transition: "All systems during migration",
	final: "New network after migration",
};

// ─── Phase classification helpers ───────────────────────────────────────────

/** Extract the local name from a potentially full URI (e.g. "http://.../Design" → "Design"). */
function localName(key: string): string {
	const idx = key.lastIndexOf("/");
	return idx >= 0 ? key.substring(idx + 1) : key;
}

/** Normalize a timeHash: collapse full-URI keys to short names so lookups work. */
function normalizeTimeHash(timeHash: Record<string, unknown>): Record<string, unknown> {
	const result: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(timeHash)) {
		result[localName(key)] = value;
	}
	return result;
}

/** Known decommissioned-only key. */
const DECOMMISSIONED_KEY = "Decommissioned";

/** Returns true if the node only has a Decommissioned phase (legacy ICD being retired). */
function isDecommissionedNode(node: TimeNode): boolean {
	if (!node.timeHash) return false;
	const keys = Object.keys(node.timeHash).map(localName);
	return keys.length > 0 && keys.every((k) => k === DECOMMISSIONED_KEY);
}

/** Returns true if the node has LOE-tracked implementation phases (new/future ICD). */
function isLOENode(node: TimeNode): boolean {
	if (!node.timeHash) return false;
	return Object.keys(node.timeHash).map(localName).some((k) => k !== DECOMMISSIONED_KEY);
}

// ─── Legacy phase ordering and colors ───────────────────────────────────────

/** Ordered SDLC phases matching the legacy network-timeline directive. */
const PHASE_ORDER = ["Requirements", "Design", "Develop", "Test", "Deploy"] as const;

/** Legacy phase fill colors (from network-timeline.css). */
const PHASE_COLORS: Record<string, string> = {
	Requirements: "#ECF6BD",
	Design: "#C5E5A0",
	Develop: "#9FD483",
	Test: "#79C366",
	Deploy: "#52B149",
	Completed: "#2CA02C",
	Decommissioned: "#FF0000",
	Sustainment: "#999999",
};

/** Text color for each phase badge (dark text on light backgrounds). */
const PHASE_TEXT_COLORS: Record<string, string> = {
	Requirements: "#000",
	Design: "#000",
	Develop: "#000",
	Test: "#fff",
	Deploy: "#fff",
	Completed: "#fff",
	Decommissioned: "#fff",
	Sustainment: "#fff",
};

const TRANSITION_TABLE_PHASE_RANK: Record<string, number> = {
	Completed: 0,
	Deploy: 1,
	Test: 2,
	Develop: 3,
	Design: 4,
	Requirements: 5,
	Decommissioned: 6,
	Sustainment: 7,
	"N/A": 8,
};

// ─── Cumulative LOE model (matches legacy createTableModel) ─────────────────

interface ComputedPhase {
	phase: string;
	startLOE: number;
	LOE: number;
	totalLOE: number;
	dependICDS: string[];
	gltag: string;
}

/** Compute cumulative LOE ranges for an LOE node, matching legacy createTableModel logic. */
function computePhaseRanges(node: TimeNode): ComputedPhase[] {
	if (!node.timeHash || !isLOENode(node)) return [];

	// Normalize timeHash keys so full URIs are collapsed to short names
	const th = normalizeTimeHash(node.timeHash);
	const result: ComputedPhase[] = [];
	let cumulative = 0;

	for (const phase of PHASE_ORDER) {
		const entry = th[phase] as { LOE?: number; dependICDS?: string | string[]; gltag?: string } | undefined;
		if (!entry) continue;
		const loe = typeof entry.LOE === "number" ? entry.LOE : Number(entry.LOE) || 0;
		const startLOE = cumulative;
		cumulative += loe;

		let deps: string[] = [];
		if (entry.dependICDS) {
			try {
				const raw = entry.dependICDS;
				const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
				if (Array.isArray(parsed)) {
					deps = parsed.map((d: string) => localName(d)).filter((d: string) => d && d.length > 0);
				}
			} catch {
				deps = [];
			}
		}

		result.push({
			phase,
			startLOE,
			LOE: loe,
			totalLOE: cumulative,
			dependICDS: deps,
			gltag: typeof entry.gltag === "string" ? localName(entry.gltag) : "",
		});
	}

	// Synthetic "Completed" phase (matches legacy)
	if (result.length > 0) {
		const lastDeps = result.find((r) => r.phase === "Requirements")?.dependICDS ?? [];
		result.push({
			phase: "Completed",
			startLOE: cumulative,
			LOE: 0,
			totalLOE: cumulative,
			dependICDS: lastDeps,
			gltag: "",
		});
	}

	return result;
}

/** Get the total cumulative LOE for an LOE node. */
function getTotalLOE(node: TimeNode): number {
	if (!node.timeHash) return 0;
	const th = normalizeTimeHash(node.timeHash);
	let total = 0;
	for (const phase of PHASE_ORDER) {
		const entry = th[phase] as { LOE?: number } | undefined;
		if (!entry) continue;
		const loe = typeof entry.LOE === "number" ? entry.LOE : Number(entry.LOE) || 0;
		total += loe;
	}
	return total;
}

/** Get the active phase for a node at the given slider value (matches legacy getPhaseColor logic). */
function getActivePhase(phases: ComputedPhase[], sliderValue: number): string {
	for (const p of phases) {
		if (p.phase === "Completed") {
			// Completed: startLOE reached, no end
			if (sliderValue > p.startLOE) return "Completed";
		} else {
			if (p.totalLOE >= sliderValue && sliderValue > p.startLOE) return p.phase;
			// At slider=0, if Requirements starts at 0, show Requirements
			if (sliderValue === 0 && p.startLOE === 0 && p.phase === "Requirements") return "Requirements";
		}
	}
	return "N/A";
}

/** Collect dependent ICD URIs that can be decommissioned at the given slider value.
 *  Matches legacy getDependentICDs: when a node's "Completed" totalLOE < sliderValue,
 *  its dependICDS are collectible for decommissioning. */
function getDependentICDs(
	nodePhaseMap: Map<string, ComputedPhase[]>,
	sliderValue: number,
): Set<string> {
	const deps = new Set<string>();
	for (const phases of nodePhaseMap.values()) {
		for (const p of phases) {
			if (p.phase === "Completed" && p.totalLOE < sliderValue && sliderValue > p.startLOE) {
				for (const d of p.dependICDS) deps.add(d);
			}
		}
	}
	return deps;
}

/** Get the fill color for a node at the given slider value (matches legacy getPhaseColor). */
function getPhaseColor(
	node: TimeNode,
	phases: ComputedPhase[],
	sliderValue: number,
	dependentICDs: Set<string>,
	maxLOE: number,
): string | null {
	if (!node.timeHash) return null;

	if (isDecommissionedNode(node)) {
		// Decommissioned: red when dependICDs include this node OR slider >= maxLOE
		const nodeLocalName = node.id.split("/").pop() ?? node.id;
		if (dependentICDs.has(node.id) || dependentICDs.has(nodeLocalName) || sliderValue >= maxLOE) {
			return PHASE_COLORS.Decommissioned;
		}
		return null;
	}

	if (phases.length === 0) return null;

	for (const p of phases) {
		if (p.phase === "Completed") {
			if (sliderValue > p.startLOE) return null; // Original node color
		} else if (p.totalLOE >= sliderValue && sliderValue > p.startLOE) {
			return PHASE_COLORS[p.phase] ?? null;
		} else if (sliderValue === 0 && p.startLOE === 0 && p.phase === "Requirements") {
			return PHASE_COLORS.Requirements;
		}
	}
	return null;
}

export const CompositionTimelinePage = () => {
	const { insightId } = useInsight();
	const [tooltip, setTooltip] = useState<TooltipData | null>(null);
	const [mapMode, setMapMode] = useState<MapMode>("initial");
	const [loeThreshold, setLoeThreshold] = useState(0);
	const [hideLabels, setHideLabels] = useState(false);
	const [sidebarWidth, setSidebarWidth] = useState(360);
	const isResizing = useRef(false);

	// System selector state
	const [systems, setSystems] = useState<SystemOption[]>([]);
	const [isLoadingSystems, setIsLoadingSystems] = useState(true);
	const [systemsError, setSystemsError] = useState<string | null>(null);
	const [selectedSystem, setSelectedSystem] = useState<SystemOption | null>(null);

	// Graph data state (reactor-backed)
	const [rawGraphData, setRawGraphData] = useState<RawTimeGraphData | null>(null);
	const [isLoadingGraph, setIsLoadingGraph] = useState(false);
	const [graphError, setGraphError] = useState<string | null>(null);

	// ── Phase 1: Fetch ActiveSystem list on mount ────────────────────────────
	useEffect(() => {
		if (!insightId) return;
		let cancelled = false;

		const pixel = `ListActiveSystems();`;
		setIsLoadingSystems(true);
		setSystemsError(null);

		runPixel(pixel, insightId)
			.then((response) => {
				if (cancelled) return;
				if (response.errors.length > 0) {
					setSystemsError(response.errors.join(", "));
					return;
				}
				const output = response.pixelReturn[0]?.output;
				if (Array.isArray(output)) {
					setSystems(output as SystemOption[]);
				}
			})
			.catch((err) => {
				if (!cancelled) {
					setSystemsError(err instanceof Error ? err.message : "Failed to load systems");
				}
			})
			.finally(() => { if (!cancelled) setIsLoadingSystems(false); });

		return () => { cancelled = true; };
	}, [insightId]);

	// ── Phase 2: Fetch graph data when a system is selected ──────────────────
	useEffect(() => {
		if (!insightId || !selectedSystem) return;
		let cancelled = false;

		const pixel = `GetCompositionTimeline(systemUri=["${selectedSystem.uri}"]);`;
		setIsLoadingGraph(true);
		setGraphError(null);
		setRawGraphData(null);

		runPixel(pixel, insightId)
			.then((response) => {
				if (cancelled) return;
				if (response.errors.length > 0) {
					setGraphError(response.errors.join(", "));
					return;
				}
				const output = response.pixelReturn[0]?.output as RawTimeGraphData;
				if (output && output.nodes && output.edges) {
					setRawGraphData(output);
				} else {
					setGraphError("Unexpected response format from server");
				}
			})
			.catch((err) => {
				if (!cancelled) {
					setGraphError(err instanceof Error ? err.message : "Failed to load graph");
				}
			})
			.finally(() => { if (!cancelled) setIsLoadingGraph(false); });

		return () => { cancelled = true; };
	}, [insightId, selectedSystem]);

	// Reset slider when switching modes
	useEffect(() => {
		if (mapMode !== "transition") setLoeThreshold(0);
	}, [mapMode]);

	// Drag-to-resize left sidebar
	const handleResizeStart = useCallback((e: React.MouseEvent) => {
		isResizing.current = true;
		e.preventDefault();
	}, []);

	useEffect(() => {
		const onMove = (e: MouseEvent) => {
			if (!isResizing.current) return;
			setSidebarWidth(Math.min(Math.max(e.clientX, 200), 700));
		};
		const onUp = () => { isResizing.current = false; };
		document.addEventListener("mousemove", onMove);
		document.addEventListener("mouseup", onUp);
		return () => {
			document.removeEventListener("mousemove", onMove);
			document.removeEventListener("mouseup", onUp);
		};
	}, []);

	const graphData = useMemo(
		() => rawGraphData ? getTimeGraphData(rawGraphData) : null,
		[rawGraphData],
	);

	// ── Cumulative LOE model ─────────────────────────────────────────────────

	/** Map of nodeId → computed phase ranges (only for LOE nodes). */
	const nodePhaseMap = useMemo(() => {
		const map = new Map<string, ComputedPhase[]>();
		if (!graphData) return map;
		for (const node of graphData.nodes) {
			if (isLOENode(node)) {
				map.set(node.id, computePhaseRanges(node));
			}
		}
		return map;
	}, [graphData]);

	/** Max cumulative LOE across all LOE nodes (+1, matching legacy). */
	const maxLOE = useMemo(() => {
		if (!graphData) return 100;
		let max = 0;
		for (const node of graphData.nodes) {
			max = Math.max(max, getTotalLOE(node));
		}
		return max > 0 ? max + 1 : 100;
	}, [graphData]);

	/** Dependent ICDs at current slider position (for decommissioning logic). */
	const dependentICDs = useMemo(
		() => getDependentICDs(nodePhaseMap, loeThreshold),
		[nodePhaseMap, loeThreshold],
	);

	/** Interface counts for the info box. */
	const interfaceCounts = useMemo(() => {
		if (!graphData) return { added: 0, decommissioned: 0 };
		let added = 0;
		let decommissioned = 0;
		for (const node of graphData.nodes) {
			if (node.type !== "SystemInterface" || !node.timeHash) continue;
			if (isDecommissionedNode(node)) decommissioned++;
			else if (isLOENode(node)) added++;
		}
		return { added, decommissioned };
	}, [graphData]);

	const handleTooltipChange = useCallback((t: TooltipData | null) => {
		setTooltip(t);
	}, []);

	// ── Node/Edge filtering by mode ──────────────────────────────────────────

	const { visibleNodes, visibleEdges } = useMemo(() => {
		if (!graphData) return { visibleNodes: [], visibleEdges: [] };
		let filteredNodes: TimeNode[];

		if (mapMode === "initial") {
			// Legacy network: static nodes + decommissioned nodes; hide new LOE migration nodes
			filteredNodes = graphData.nodes.filter((n) => !isLOENode(n));
		} else if (mapMode === "final") {
			// New network: static nodes + completed migration nodes; hide decommissioned legacy nodes
			filteredNodes = graphData.nodes.filter((n) => !isDecommissionedNode(n));
		} else {
			// Transition: show everything
			filteredNodes = graphData.nodes;
		}

		// First pass: filter edges to only those where both endpoints are visible
		let visibleIds = new Set(filteredNodes.map((n) => n.id));
		let filteredEdges = graphData.edges.filter(
			(e) => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId),
		);

		// Second pass: remove non-System nodes that have no remaining edges (stranded DataObjects/Interfaces)
		const connectedIds = new Set<string>();
		for (const e of filteredEdges) {
			connectedIds.add(e.sourceId);
			connectedIds.add(e.targetId);
		}
		filteredNodes = filteredNodes.filter(
			(n) => n.type === "System" || connectedIds.has(n.id),
		);

		// Re-filter edges in case the second pass removed any nodes
		visibleIds = new Set(filteredNodes.map((n) => n.id));
		filteredEdges = filteredEdges.filter(
			(e) => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId),
		);

		return { visibleNodes: filteredNodes, visibleEdges: filteredEdges };
	}, [mapMode, graphData]);

	// ── Color overrides (transition mode: legacy phase colors) ───────────────

	const colorOverrides = useMemo(() => {
		if (mapMode !== "transition") return undefined;
		const map = new Map<string, string>();
		for (const n of visibleNodes) {
			const phases = nodePhaseMap.get(n.id) ?? [];
			const color = getPhaseColor(n, phases, loeThreshold, dependentICDs, maxLOE);
			if (color) map.set(n.id, color);
		}
		return map;
	}, [mapMode, loeThreshold, maxLOE, visibleNodes, nodePhaseMap, dependentICDs]);

	// ── Table row model (matches legacy createTableModel) ────────────────────

	interface TableRow {
		name: string;
		uri: string;
		phase: string;
		LOE: number;
		totalLOE: number;
		gltag: string;
	}

	const tableRows = useMemo((): TableRow[] => {
		const icdNodes = visibleNodes.filter((n) => n.type === "SystemInterface");
		const rows: TableRow[] = [];

		for (const node of icdNodes) {
			if (mapMode === "initial") {
				// Initial state: Sustainment (no timeHash) or Decommissioned
				if (isDecommissionedNode(node)) {
					rows.push({ name: node.label, uri: node.id, phase: "Decommissioned", LOE: 0, totalLOE: 0, gltag: "" });
				} else {
					rows.push({ name: node.label, uri: node.id, phase: "Sustainment", LOE: 0, totalLOE: 0, gltag: "" });
				}
			} else if (mapMode === "final") {
				if (isLOENode(node)) {
					rows.push({ name: node.label, uri: node.id, phase: "Completed", LOE: getTotalLOE(node), totalLOE: getTotalLOE(node), gltag: "" });
				} else {
					rows.push({ name: node.label, uri: node.id, phase: "Sustainment", LOE: 0, totalLOE: 0, gltag: "" });
				}
			} else {
				// Transition: show active phase based on slider
				if (isDecommissionedNode(node)) {
					const nodeLocalName = node.id.split("/").pop() ?? node.id;
					const isRed = dependentICDs.has(node.id) || dependentICDs.has(nodeLocalName) || loeThreshold >= maxLOE;
					rows.push({ name: node.label, uri: node.id, phase: isRed ? "Decommissioned" : "Sustainment", LOE: 0, totalLOE: 0, gltag: "" });
				} else if (isLOENode(node)) {
					const phases = nodePhaseMap.get(node.id) ?? [];
					const activePhase = getActivePhase(phases, loeThreshold);
					const activeEntry = phases.find((p) => p.phase === activePhase);
					rows.push({
						name: node.label, uri: node.id, phase: activePhase,
						LOE: activeEntry?.LOE ?? 0, totalLOE: getTotalLOE(node),
						gltag: activeEntry?.gltag ?? "",
					});
				} else {
					rows.push({ name: node.label, uri: node.id, phase: "Sustainment", LOE: 0, totalLOE: 0, gltag: "" });
				}
			}
		}

		if (mapMode === "transition") {
			return rows.sort((a, b) => {
				const ra = TRANSITION_TABLE_PHASE_RANK[a.phase] ?? 99;
				const rb = TRANSITION_TABLE_PHASE_RANK[b.phase] ?? 99;
				if (ra !== rb) return ra - rb;
				return a.name.localeCompare(b.name);
			});
		}

		return rows.sort((a, b) => a.name.localeCompare(b.name));
	}, [visibleNodes, mapMode, loeThreshold, nodePhaseMap, dependentICDs, maxLOE]);

	return (
		<div className="flex flex-col h-full">
			{/* Header */}
			<header className="shrink-0 border-b border-gray-200 bg-white px-6 py-3">
				<div className="flex items-center gap-4">
					<h1 className="text-lg font-semibold text-gray-900 shrink-0">
						{graphData?.title ?? "How does the composition of this system change over time?"}
					</h1>
					{/* System selector */}
					<select
						className="ml-auto border border-gray-300 rounded px-2 py-1 text-sm text-gray-700 bg-white disabled:opacity-50"
						value={selectedSystem?.uri ?? ""}
						onChange={(e) => {
							const found = systems.find((s) => s.uri === e.target.value);
							setSelectedSystem(found ?? null);
						}}
						disabled={isLoadingSystems}
					>
						<option value="">
							{isLoadingSystems ? "Loading systems…" : systemsError ? "Error loading systems" : "Select a system…"}
						</option>
						{systems.map((s) => (
							<option key={s.uri} value={s.uri}>{s.label}</option>
						))}
					</select>
				</div>
				<p className="text-sm text-gray-500 mt-0.5">
					{isLoadingGraph
						? "Loading graph…"
						: graphError
						? `Error: ${graphError}`
						: graphData
						? `${visibleNodes.length} nodes · ${visibleEdges.length} edges`
						: "Select a system to load the timeline graph"}
				</p>
			</header>

			{/* Main content: left table + graph + right sidebar */}
			<div className="flex-1 flex overflow-hidden">
				{/* Left Sidebar — Interface Modernization Table (resizable) */}
				<aside
					className="shrink-0 border-r border-gray-200 bg-gray-50 flex flex-col overflow-hidden relative"
					style={{ width: sidebarWidth }}
				>
					<div className="px-3 py-2 border-b border-gray-200">
						<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
							Interface Modernization Status
						</h2>
						<span className="text-[10px] text-gray-400">
							{tableRows.length} interfaces
						</span>
					</div>
					<div className="flex-1 overflow-auto">
						<table className="w-full text-[11px] border-collapse">
							<thead className="sticky top-0 bg-gray-100 z-10">
								<tr>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200 w-[40%]">
										ICD
									</th>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200 w-[25%]">
										Phase
									</th>
									<th className="text-right px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200 w-[15%]">
										LOE
									</th>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200 w-[20%]">
										GL Tag
									</th>
								</tr>
							</thead>
							<tbody>
								{tableRows.map((row) => {
									const bg = PHASE_COLORS[row.phase];
									const fg = PHASE_TEXT_COLORS[row.phase] ?? "#000";
									return (
										<tr
											key={`${row.uri}-${row.phase}`}
											className="border-b border-gray-100 hover:bg-gray-100/60"
										>
											<td className="px-2 py-1 text-gray-800 truncate max-w-[180px]" title={row.name}>
												{row.name.replace(/_/g, " ")}
											</td>
											<td className="px-1 py-0.5">
													{mapMode === "initial" ? (
														<span className="text-[10px] text-gray-400">N/A</span>
													) : (
														<span
															className="inline-block px-1.5 py-0.5 rounded text-[10px] font-bold text-center whitespace-nowrap"
															style={bg ? { backgroundColor: bg, color: fg } : undefined}
														>
															{row.phase}
														</span>
												)}
											</td>
											<td className="px-2 py-1 text-gray-600 text-right tabular-nums">
												{row.LOE > 0 ? row.LOE : "—"}
											</td>
											<td className="px-2 py-1 text-gray-600 truncate max-w-[80px]" title={row.gltag}>
												{row.gltag || "—"}
											</td>
										</tr>
									);
								})}
							</tbody>
						</table>
					</div>
					{/* Resize handle */}
					<div
						onMouseDown={handleResizeStart}
						className="absolute top-0 right-0 w-1.5 h-full cursor-col-resize hover:bg-blue-400/40 active:bg-blue-500/50 z-20"
					/>
				</aside>

				{/* Center: Graph */}
				<div className="flex-1 relative">
					{isLoadingGraph ? (
						<div className="flex items-center justify-center h-full text-gray-400 text-sm">Loading graph…</div>
					) : graphError ? (
						<div className="flex items-center justify-center h-full text-red-500 text-sm">{graphError}</div>
					) : !graphData ? (
						<div className="flex items-center justify-center h-full text-gray-400 text-sm">Select a system above to view the timeline graph.</div>
					) : (
						<>
							<TimelineGraph
								nodes={visibleNodes}
								edges={visibleEdges}
								selectedPhase={null}
								colorOverrides={colorOverrides}
								hideLabels={hideLabels}
								onTooltipChange={handleTooltipChange}
							/>
							<GraphLegend entries={graphData.legend} />
							<GraphTooltip tooltip={tooltip} />

							{/* Interface count info box (Transition mode) */}
							{mapMode === "transition" && (
								<div className="absolute bottom-4 left-4 bg-white/90 border border-gray-300 rounded-lg px-4 py-3 shadow-sm text-xs">
									<div className="text-gray-700">
										Interfaces Added: <span className="font-semibold text-green-700">{interfaceCounts.added}</span>
									</div>
									<div className="text-gray-700 mt-1">
										Interfaces Decommissioned: <span className="font-semibold text-red-600">{interfaceCounts.decommissioned}</span>
									</div>
								</div>
							)}
						</>
					)}
				</div>

				{/* Right Sidebar — Controls */}
				<aside className="w-64 shrink-0 border-l border-gray-200 bg-gray-50 p-4 flex flex-col gap-4 overflow-y-auto">
					<div>
						<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
							Update State
						</h2>
						<label className="flex items-center gap-2 text-sm text-gray-700 mb-3 cursor-pointer select-none">
							<input
								type="checkbox"
								checked={hideLabels}
								onChange={(e) => setHideLabels(e.target.checked)}
								className="accent-blue-600 w-3.5 h-3.5"
							/>
							Hide labels
						</label>
						<div className="flex flex-col gap-2">
							{(["initial", "transition", "final"] as MapMode[]).map((mode) => (
								<button
									key={mode}
									type="button"
									onClick={() => setMapMode(mode)}
									className={`text-left px-3 py-2.5 rounded-md transition-colors ${
										mapMode === mode
											? "bg-blue-600 text-white shadow-sm"
											: "bg-white text-gray-700 border border-gray-200 hover:bg-gray-100"
									}`}
								>
									<div className="font-medium text-sm">
										{MODE_LABELS[mode]}
									</div>
									<div
										className={`text-xs mt-0.5 ${
											mapMode === mode
												? "text-blue-100"
												: "text-gray-400"
										}`}
									>
										{MODE_DESCRIPTIONS[mode]}
									</div>
								</button>
							))}
						</div>
					</div>

					{/* Transition Modernization LOE Slider — Transition mode only */}
					{mapMode === "transition" && maxLOE > 1 && (
						<div className="mt-2">
							<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
								Transition Modernization LOE Slider
							</h2>

							{/* Current value */}
							<div className="flex justify-between items-baseline mb-1.5">
								<span className="text-xs font-medium text-emerald-700">
									{loeThreshold === 0
										? "Not started"
										: loeThreshold >= maxLOE
										? "Migration complete"
										: "In progress"}
								</span>
								<span className="text-[10px] text-gray-400">
									{loeThreshold} / {maxLOE}
								</span>
							</div>

							{/* Range input */}
							<input
								type="range"
								min={0}
								max={maxLOE}
								step={1}
								value={loeThreshold}
								onChange={(e) => setLoeThreshold(Number(e.target.value))}
								className="w-full h-1.5 accent-emerald-600 cursor-pointer"
							/>

							{/* Phase color legend */}
							<div className="mt-3">
								<div className="text-[10px] font-semibold text-gray-400 uppercase tracking-wide mb-1.5">
									Phase Colors
								</div>
								<div className="space-y-0.5">
									{[...PHASE_ORDER, "Completed" as const, "Decommissioned" as const].map((phase) => (
										<div key={phase} className="flex items-center gap-1.5 py-0.5">
											<span
												className="w-3 h-3 rounded-sm shrink-0 border border-gray-300"
												style={{ backgroundColor: PHASE_COLORS[phase] }}
											/>
											<span className="text-[10px] text-gray-600">{phase}</span>
										</div>
									))}
								</div>
							</div>

							{/* New Interfaces list */}
							<div className="mt-3">
								<div className="text-[10px] font-semibold text-gray-400 uppercase tracking-wide mb-1.5">
									New Interfaces ({interfaceCounts.added})
								</div>
								<div className="space-y-0.5 overflow-y-auto pr-0.5" style={{ maxHeight: 180 }}>
									{(graphData?.nodes ?? [])
										.filter((n) => isLOENode(n) && n.type === "SystemInterface")
										.sort((a, b) => a.label.localeCompare(b.label))
										.map((node) => {
											const phases = nodePhaseMap.get(node.id) ?? [];
											const color = getPhaseColor(node, phases, loeThreshold, dependentICDs, maxLOE);
											const activePhase = getActivePhase(phases, loeThreshold);
											return (
												<div key={node.id} className="flex items-center gap-1.5 py-0.5">
													<span
														className="w-2.5 h-2.5 rounded-full shrink-0 border border-gray-300"
														style={{ backgroundColor: color ?? node.color }}
													/>
													<span className="truncate text-[10px] text-gray-600">
														{node.label.replace(/_/g, " ")}
													</span>
													<span
														className="ml-auto text-[9px] font-medium px-1 rounded"
														style={{
															backgroundColor: PHASE_COLORS[activePhase] ?? "#eee",
															color: PHASE_TEXT_COLORS[activePhase] ?? "#000",
														}}
													>
														{activePhase}
													</span>
												</div>
											);
										})}
								</div>
							</div>
						</div>
					)}
				</aside>
			</div>
		</div>
	);
};
