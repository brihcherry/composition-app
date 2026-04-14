// CompositionTimelinePage.tsx — Full-screen page for insight #21:
// "How does the composition of this system change over time?"
//
// Layout: left table | center graph | right sidebar
// Three map modes: Initial (legacy), Transition (migration in progress), Final (new system)

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

/** Returns true if the node is a legacy node being replaced (only has a Decommissioned phase). */
function isDecommissionedNode(node: TimeNode): boolean {
	if (!node.timeHash) return false;
	const keys = Object.keys(node.timeHash);
	return keys.length > 0 && keys.every((k) => k === "Decommissioned");
}

/** Returns true if the node is a new migration node with LOE-tracked implementation phases. */
function isLOENode(node: TimeNode): boolean {
	if (!node.timeHash) return false;
	return Object.keys(node.timeHash).some((k) => k !== "Decommissioned");
}

/** Ordered SDLC phases (without Decommissioned) used for LOE computation. */
const PHASE_ORDER_LOE = ["Requirements", "Design", "Develop", "Test"] as const;

/** Fill colors from cream (not started) to dark green (all done). */
const LOE_COLORS = [
	"#FFFBEB", // not started — cream
	"#D1FAE5", // Requirements done — emerald-100
	"#6EE7B7", // Design done — emerald-300
	"#10B981", // Develop done — emerald-500
	"#065F46", // All done — emerald-900
] as const;

/** Stroke colors paired with LOE_COLORS (same index). */
const LOE_STROKES = [
	"#c9a84c", // amber
	"#059669", // emerald-600
	"#047857", // emerald-700
	"#064E3B", // emerald-900
	"#022c22", // near-black green
] as const;

/** Compute fill color for a transition LOE node based on current slider threshold. */
function getTransitionNodeColor(node: TimeNode, loeThreshold: number): string {
	if (!node.timeHash) return LOE_COLORS[0];
	let cumulative = 0;
	let completed = 0;
	for (const phase of PHASE_ORDER_LOE) {
		const loe = (node.timeHash[phase] as { LOE?: number } | undefined)?.LOE ?? 0;
		if (loe === 0) continue;
		cumulative += loe;
		if (loeThreshold >= cumulative) completed++;
		else break;
	}
	return LOE_COLORS[Math.min(completed, LOE_COLORS.length - 1)];
}

/** Get the current active phase for an LOE node at the given slider threshold. */
function getCurrentPhase(node: TimeNode, loeThreshold: number): string {
	if (!node.timeHash || !isLOENode(node)) return "N/A";
	let cumulative = 0;
	for (const phase of PHASE_ORDER_LOE) {
		const loe = (node.timeHash[phase] as { LOE?: number } | undefined)?.LOE ?? 0;
		if (loe === 0) continue;
		cumulative += loe;
		if (loeThreshold < cumulative) return phase;
	}
	return "Complete";
}

/** True if a color is dark enough to need white text. */
function needsLightText(color: string): boolean {
	return color === "#10B981" || color === "#065F46";
}

export const CompositionTimelinePage = () => {
	const { insightId } = useInsight();
	const [tooltip, setTooltip] = useState<TooltipData | null>(null);
	const [mapMode, setMapMode] = useState<MapMode>("initial");
	const [loeThreshold, setLoeThreshold] = useState(0);
	const [hideLabels, setHideLabels] = useState(false);
	const [sidebarWidth, setSidebarWidth] = useState(320);
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

	/** Max cumulative LOE across all LOE nodes (slider upper bound). */
	const maxLOE = useMemo(() => {
		if (!graphData) return 100;
		let max = 0;
		for (const node of graphData.nodes) {
			if (!isLOENode(node) || !node.timeHash) continue;
			const total = PHASE_ORDER_LOE.reduce(
				(sum, phase) =>
					sum +
					((node.timeHash![phase] as { LOE?: number } | undefined)?.LOE ?? 0),
				0,
			);
			max = Math.max(max, total);
		}
		return max || 100;
	}, [graphData]);

	const handleTooltipChange = useCallback((t: TooltipData | null) => {
		setTooltip(t);
	}, []);

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
		// System nodes are always shown even if isolated, as they anchor the layout
		const connectedIds = new Set<string>();
		for (const e of filteredEdges) {
			connectedIds.add(e.sourceId);
			connectedIds.add(e.targetId);
		}
		filteredNodes = filteredNodes.filter(
			(n) => n.type === "System" || connectedIds.has(n.id),
		);

		// Re-filter edges in case the second pass removed any nodes (shouldn't happen but keeps it consistent)
		visibleIds = new Set(filteredNodes.map((n) => n.id));
		filteredEdges = filteredEdges.filter(
			(e) => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId),
		);

		return { visibleNodes: filteredNodes, visibleEdges: filteredEdges };
	}, [mapMode, graphData]);

	/** Color overrides computed from slider — changes do NOT trigger graph re-init. */
	const colorOverrides = useMemo(() => {
		if (mapMode !== "transition") return undefined;
		const map = new Map<string, string>();
		const allComplete = loeThreshold >= maxLOE;
		for (const n of visibleNodes) {
			if (isLOENode(n)) {
				map.set(n.id, getTransitionNodeColor(n, loeThreshold));
			} else if (isDecommissionedNode(n) && allComplete) {
				map.set(n.id, "#EF4444");
			}
		}
		return map;
	}, [mapMode, loeThreshold, maxLOE, visibleNodes]);

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
				{/* Left Sidebar — Node Table (resizable) */}
				<aside
					className="shrink-0 border-r border-gray-200 bg-gray-50 flex flex-col overflow-hidden relative"
					style={{ width: sidebarWidth }}
				>
					<div className="px-3 py-2 border-b border-gray-200">
						<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
							Interface Modernization Status
						</h2>
						<span className="text-[10px] text-gray-400">
							{visibleNodes.length} nodes
						</span>
					</div>
					<div className="flex-1 overflow-auto">
						<table className="w-full text-[11px] border-collapse">
							<thead className="sticky top-0 bg-gray-100 z-10">
								<tr>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200">
										ICD
									</th>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200">
										Phase
									</th>
									<th className="text-right px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200">
										LOE
									</th>
									<th className="text-left px-2 py-1.5 font-semibold text-gray-600 border-b border-gray-200">
										GL Tag
									</th>
								</tr>
							</thead>
							<tbody>
								{visibleNodes
									.filter((n) => n.type === "SystemInterface")
									.sort((a, b) => a.label.localeCompare(b.label))
									.map((node) => {
										const loeNode = isLOENode(node);
										const phases = node.timeHash
											? Object.keys(node.timeHash).filter((k) => k !== "Decommissioned")
											: [];
										const totalLOE = phases.reduce(
											(sum, p) =>
												sum +
												((node.timeHash?.[p] as { LOE?: number } | undefined)?.LOE ?? 0),
											0,
										);
										const glTags = [
											...new Set(
												phases
													.map((p) => (node.timeHash?.[p] as { gltag?: string } | undefined)?.gltag)
													.filter(Boolean),
											),
										];

										// Phase display depends on map mode
										let phaseLabel = "N/A";
										let phaseBg: string | undefined;
										let phaseText: string | undefined;

										if (mapMode === "transition" && loeNode) {
											phaseLabel = getCurrentPhase(node, loeThreshold);
											const fill = getTransitionNodeColor(node, loeThreshold);
											phaseBg = fill;
											phaseText = needsLightText(fill) ? "#fff" : "#1f2937";
										} else if (mapMode === "final" && loeNode) {
											phaseLabel = "Complete";
										}

										return (
											<tr
												key={node.id}
												className="border-b border-gray-100 hover:bg-gray-100/60"
											>
												<td className="px-2 py-1 text-gray-800 truncate max-w-[180px]" title={node.label}>
													{node.label}
												</td>
												<td className="px-1 py-0.5">
													<span
														className="inline-block px-1.5 py-0.5 rounded text-[10px] font-medium"
														style={
															phaseBg
																? { backgroundColor: phaseBg, color: phaseText }
																: undefined
														}
													>
														{phaseLabel}
													</span>
												</td>
												<td className="px-2 py-1 text-gray-600 text-right tabular-nums">
													{totalLOE > 0 ? totalLOE : "—"}
												</td>
												<td className="px-2 py-1 text-gray-600 truncate max-w-[80px]" title={glTags.join(", ")}>
													{glTags.length > 0 ? glTags.join(", ") : "—"}
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
						</>
					)}
				</div>

				{/* Right Sidebar — Controls */}
				<aside className="w-64 shrink-0 border-l border-gray-200 bg-gray-50 p-4 flex flex-col gap-4 overflow-y-auto">
					<div>
						<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
							Tools
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

					{/* LOE Progress Slider — Transition mode only */}
					{mapMode === "transition" && maxLOE > 0 && (
						<div className="mt-2">
							<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
								Transition State By LOE
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
								value={loeThreshold}
								onChange={(e) =>
									setLoeThreshold(Number(e.target.value))
								}
								className="w-full h-1.5 accent-emerald-600 cursor-pointer"
							/>

							{/* Interface list */}
							<div className="text-[10px] font-semibold text-gray-400 uppercase tracking-wide mb-1.5">
								New Interfaces
							</div>
							<div className="space-y-0.5 overflow-y-auto pr-0.5" style={{ maxHeight: 180 }}>
								{(graphData?.nodes ?? [])
									.filter(
										(n) =>
											isLOENode(n) &&
											n.type === "SystemInterface",
									)
									.sort((a, b) => a.label.localeCompare(b.label))
									.map((node) => {
										const fill = getTransitionNodeColor(
											node,
											loeThreshold,
										);
										const idx = LOE_COLORS.indexOf(
											fill as (typeof LOE_COLORS)[number],
										);
										const stroke = LOE_STROKES[idx] ?? "#c9a84c";
										const cleanLabel = node.label
											.replace("MHS_GENESIS-NMIS-", "")
											.replace(/_/g, " ");
										return (
											<div
												key={node.id}
												className="flex items-center gap-1.5 py-0.5"
											>
												<span
													className="w-2.5 h-2.5 rounded-full shrink-0 border"
													style={{
														backgroundColor: fill,
														borderColor: stroke,
													}}
												/>
												<span className="truncate text-[10px] text-gray-600">
													{cleanLabel}
												</span>
											</div>
										);
									})}
							</div>
						</div>
					)}
				</aside>
			</div>
		</div>
	);
};
