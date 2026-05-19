// TimelineGraph.tsx — D3 force-directed graph with phase-aware highlighting.
// Fork of NetworkGraph.tsx, extended to support the time dimension from insight #21.
// Nodes with timeHash data for the selected phase are shown at full opacity;
// others are dimmed. When no phase is selected ("All"), everything is shown normally.

import { useEffect, useRef, useCallback } from "react";
import * as d3 from "d3";
import type { TooltipData } from "@/types/graph";
import type { TimeNode, TimeEdge } from "@/types/timeGraph";

interface TimelineGraphProps {
	nodes: TimeNode[];
	edges: TimeEdge[];
	selectedPhase: string | null; // null = show all
	colorOverrides?: Map<string, string>; // nodeId → fill color (applied without re-init)
	hideLabels?: boolean;
	hideInterfaceLabels?: boolean;
	onTooltipChange: (tooltip: TooltipData | null) => void;
}

// Force layout parameters
const CHARGE_STRENGTH = -200;
const LINK_DISTANCE = 60;
const CENTER_GRAVITY = 0.09;
const NODE_RADIUS = 8;
const LABEL_FONT_SIZE = 10;
const ZOOM_EXTENT: [number, number] = [0.1, 10];
const CURVE_OFFSET = 30;
const TRANSITION_MS = 400;

/** Maps LOE/transition fill colors to appropriate border strokes. Falls back to white. */
const STROKE_MAP: Record<string, string> = {
	// Legacy phase colors (from network-timeline.css)
	"#ECF6BD": "#b8c99a", // Requirements → darker yellow-green
	"#C5E5A0": "#97b87a", // Design → darker green
	"#9FD483": "#7ab55e", // Develop → darker green
	"#79C366": "#5a9f48", // Test → darker green
	"#52B149": "#3d8a35", // Deploy → darker green
	"#2CA02C": "#1f7a1f", // Completed → dark green
	"#FF0000": "#B91C1C", // Decommissioned → dark red
	// Original gradient colors (backwards compatibility)
	"#FFFBEB": "#c9a84c", // cream → amber
	"#D1FAE5": "#059669", // emerald-100 → emerald-600
	"#6EE7B7": "#047857", // emerald-300 → emerald-700
	"#10B981": "#064E3B", // emerald-500 → emerald-900
	"#065F46": "#022c22", // emerald-900 → near-black green
	"#EF4444": "#B91C1C", // red → red-700
};

function getNodeStroke(color: string): string {
	return STROKE_MAP[color] ?? "#fff";
}

export const TimelineGraph = ({
	nodes,
	edges,
	selectedPhase,
	colorOverrides,
	hideLabels = false,
	hideInterfaceLabels = false,
	onTooltipChange,
}: TimelineGraphProps) => {
	const containerRef = useRef<HTMLDivElement>(null);
	const svgRef = useRef<SVGSVGElement>(null);
	const simulationRef = useRef<d3.Simulation<TimeNode, TimeEdge> | null>(null);
	const onTooltipChangeRef = useRef(onTooltipChange);
	onTooltipChangeRef.current = onTooltipChange;

	const nodeGroupRef = useRef<d3.Selection<SVGGElement, TimeNode, SVGGElement, unknown> | null>(null);
	const linkGroupRef = useRef<d3.Selection<SVGPathElement, TimeEdge, SVGGElement, unknown> | null>(null);

	const nodeLabelMap = useRef(new Map<string, string>());
	const colorOverridesRef = useRef(colorOverrides);
	colorOverridesRef.current = colorOverrides;

	const hideLabelsRef = useRef(hideLabels);
	hideLabelsRef.current = hideLabels;

	const hideInterfaceLabelsRef = useRef(hideInterfaceLabels);
	hideInterfaceLabelsRef.current = hideInterfaceLabels;

	/** Determine if a node is "active" for the selected phase. */
	const isNodeActiveForPhase = useCallback(
		(node: TimeNode, phase: string | null): boolean => {
			if (!phase) return true; // "All" mode
			if (!node.hasTimeData) return true; // nodes without time data are always visible
			return node.timeHash !== null && phase in node.timeHash;
		},
		[],
	);

	/** Determine if an edge is "active" — both endpoints must be active. */
	const isEdgeActiveForPhase = useCallback(
		(edge: TimeEdge, phase: string | null, nodeMap: Map<string, TimeNode>): boolean => {
			if (!phase) return true;
			const src = nodeMap.get(edge.sourceId);
			const tgt = nodeMap.get(edge.targetId);
			if (!src || !tgt) return false;
			return isNodeActiveForPhase(src, phase) && isNodeActiveForPhase(tgt, phase);
		},
		[isNodeActiveForPhase],
	);

	const initGraph = useCallback(() => {
		const svg = d3.select(svgRef.current);
		const container = containerRef.current;
		if (!container || !svgRef.current) return;

		const width = container.clientWidth;
		const height = container.clientHeight;

		svg.attr("width", width).attr("height", height);
		svg.selectAll("*").remove();

		// Build label lookup
		const labelMap = new Map<string, string>();
		for (const n of nodes) {
			labelMap.set(n.id, n.label);
		}
		nodeLabelMap.current = labelMap;

		// Arrow marker
		const defs = svg.append("defs");
		defs.append("marker")
			.attr("id", "arrowhead-time")
			.attr("viewBox", "0 -5 10 10")
			.attr("refX", NODE_RADIUS + 10)
			.attr("refY", 0)
			.attr("markerWidth", 6)
			.attr("markerHeight", 6)
			.attr("orient", "auto")
			.append("path")
			.attr("d", "M0,-5L10,0L0,5")
			.attr("fill", "#999");

		// Zoom group
		const g = svg.append("g");

		const zoomBehavior = d3
			.zoom<SVGSVGElement, unknown>()
			.scaleExtent(ZOOM_EXTENT)
			.on("zoom", (event) => {
				g.attr("transform", event.transform);
			});

		svg.call(zoomBehavior);

		// Reset D3-mutated edge source/target back to string IDs so every simulation
		// run resolves them fresh against the current nodes array. Without this,
		// D3 reuses stale object references from a previous run, creating a phantom
		// node at the SVG origin.
		const linkData = edges.map((e) => ({
			...e,
			source: e.sourceId,
			target: e.targetId,
		}));

		// Edges
		const linkGroup = g
			.append("g")
			.attr("class", "links")
			.selectAll("path")
			.data(linkData)
			.join("path")
			.attr("fill", "none")
			.attr("stroke", "#999")
			.attr("stroke-opacity", 0.6)
			.attr("stroke-width", 1.5)
			.attr("marker-end", "url(#arrowhead-time)")
			.on("mouseenter", (event: MouseEvent, d: TimeEdge) => {
				onTooltipChangeRef.current({
					x: event.clientX + 12,
					y: event.clientY + 12,
					type: "edge",
					edge: d,
					sourceLabel: labelMap.get(d.sourceId) || "",
					targetLabel: labelMap.get(d.targetId) || "",
				});
			})
			.on("mouseleave", () => {
				onTooltipChangeRef.current(null);
			});

		linkGroupRef.current = linkGroup as unknown as typeof linkGroupRef.current;

		// Nodes
		const nodeGroup = g
			.append("g")
			.attr("class", "nodes")
			.selectAll("g")
			.data(nodes)
			.join("g")
			.attr("cursor", "grab");

		nodeGroupRef.current = nodeGroup;

		// Node circles
		nodeGroup
			.append("circle")
			.attr("r", NODE_RADIUS)
			.attr("fill", (d) => colorOverridesRef.current?.get(d.id) ?? d.color)
			.attr("stroke", (d) => getNodeStroke(colorOverridesRef.current?.get(d.id) ?? d.color))
			.attr("stroke-width", 1.5);

		// Node labels
		nodeGroup
			.append("text")
			.text((d) => d.label)
			.attr("x", 0)
			.attr("y", NODE_RADIUS + LABEL_FONT_SIZE + 2)
			.attr("text-anchor", "middle")
			.attr("font-size", `${LABEL_FONT_SIZE}px`)
			.attr("fill", "#333")
			.attr("pointer-events", "none")
			.attr("display", (d) =>
				hideLabelsRef.current || (hideInterfaceLabelsRef.current && d.type === "SystemInterface")
					? "none"
					: null,
			);

		// Node hover
		nodeGroup
			.on("mouseenter", (event: MouseEvent, d: TimeNode) => {
				d3.select(event.currentTarget as SVGGElement)
					.select("circle")
					.attr("stroke", "#000")
					.attr("stroke-width", 2.5);
				onTooltipChangeRef.current({
					x: event.clientX + 12,
					y: event.clientY + 12,
					type: "node",
					node: d,
				});
			})
			.on("mousemove", (event: MouseEvent, d: TimeNode) => {
				onTooltipChangeRef.current({
					x: event.clientX + 12,
					y: event.clientY + 12,
					type: "node",
					node: d,
				});
			})
			.on("mouseleave", (event: MouseEvent, d: TimeNode) => {
				const fill = colorOverridesRef.current?.get(d.id) ?? d.color;
				d3.select(event.currentTarget as SVGGElement)
					.select("circle")
					.attr("stroke", getNodeStroke(fill))
					.attr("stroke-width", 1.5);
				onTooltipChangeRef.current(null);
			});

		// Drag behavior
		const drag = d3
			.drag<SVGGElement, TimeNode>()
			.on("start", (event, d) => {
				if (!event.active) simulation.alphaTarget(0.3).restart();
				d.fx = d.x;
				d.fy = d.y;
				d3.select(event.sourceEvent.currentTarget).attr("cursor", "grabbing");
			})
			.on("drag", (event, d) => {
				d.fx = event.x;
				d.fy = event.y;
			})
			.on("end", (event, d) => {
				if (!event.active) simulation.alphaTarget(0);
				d.fx = null;
				d.fy = null;
				d3.select(event.sourceEvent.currentTarget).attr("cursor", "grab");
			});

		nodeGroup.call(drag);

		// Force simulation
		const simulation = d3
			.forceSimulation<TimeNode>(nodes)
			.force(
				"link",
				d3
					.forceLink<TimeNode, TimeEdge>(linkData)
					.id((d) => d.id)
					.distance(LINK_DISTANCE),
			)
			.force("charge", d3.forceManyBody().strength(CHARGE_STRENGTH))
			.force("center", d3.forceCenter(width / 2, height / 2))
			.force("gravity", d3.forceRadial(0, width / 2, height / 2).strength(CENTER_GRAVITY))
			.force("collide", d3.forceCollide(NODE_RADIUS + 4))
			.on("tick", () => {
				linkGroup.attr("d", (d) => {
					const sx = (d.source as TimeNode).x ?? 0;
					const sy = (d.source as TimeNode).y ?? 0;
					const tx = (d.target as TimeNode).x ?? 0;
					const ty = (d.target as TimeNode).y ?? 0;

					const mx = (sx + tx) / 2;
					const my = (sy + ty) / 2;
					const dx = tx - sx;
					const dy = ty - sy;
					const len = Math.sqrt(dx * dx + dy * dy) || 1;
					const px = -dy / len;
					const py = dx / len;
					const cx = mx + px * CURVE_OFFSET;
					const cy = my + py * CURVE_OFFSET;

					return `M ${sx},${sy} Q ${cx},${cy} ${tx},${ty}`;
				});

				nodeGroup.attr("transform", (d) => `translate(${d.x ?? 0},${d.y ?? 0})`);
			});

		simulationRef.current = simulation;

		// Fit graph into view after simulation settles
		simulation.on("end", () => {
			const bounds = (g.node() as SVGGElement)?.getBBox();
			if (!bounds) return;
			const pad = 40;
			const fullWidth = bounds.width + pad * 2;
			const fullHeight = bounds.height + pad * 2;
			const scale = Math.min(width / fullWidth, height / fullHeight, 1);
			const tx = (width - bounds.width * scale) / 2 - bounds.x * scale;
			const ty = (height - bounds.height * scale) / 2 - bounds.y * scale;
			svg.transition()
				.duration(500)
				.call(
					zoomBehavior.transform,
					d3.zoomIdentity.translate(tx, ty).scale(scale),
				);
		});
	}, [nodes, edges]);

	// Initialize graph and handle resizes
	useEffect(() => {
		initGraph();

		const observer = new ResizeObserver(() => {
			if (simulationRef.current) {
				simulationRef.current.stop();
			}
			initGraph();
		});

		if (containerRef.current) {
			observer.observe(containerRef.current);
		}

		return () => {
			observer.disconnect();
			if (simulationRef.current) {
				simulationRef.current.stop();
			}
		};
	}, [initGraph]);

	// Apply color overrides without reinitializing the graph (slider changes).
	// Runs after initGraph so nodeGroupRef is always populated.
	useEffect(() => {
		const nodeGroup = nodeGroupRef.current;
		if (!nodeGroup) return;
		const overrides = colorOverrides;

		nodeGroup
			.select("circle")
			.attr("fill", (d) => overrides?.get(d.id) ?? d.color)
			.attr("stroke", (d) => getNodeStroke(overrides?.get(d.id) ?? d.color));
	}, [colorOverrides]);

	// Toggle all-label visibility without re-init
	useEffect(() => {
		const nodeGroup = nodeGroupRef.current;
		if (!nodeGroup) return;
		nodeGroup.selectAll<SVGTextElement, TimeNode>("text").attr("display", (d) =>
			hideLabels || (hideInterfaceLabels && d.type === "SystemInterface") ? "none" : null,
		);
	}, [hideLabels, hideInterfaceLabels]);

	// Apply phase-based highlighting when selectedPhase changes
	useEffect(() => {
		const nodeGroup = nodeGroupRef.current;
		const linkGroup = linkGroupRef.current;
		if (!nodeGroup || !linkGroup) return;

		const nodeMap = new Map<string, TimeNode>();
		for (const n of nodes) {
			nodeMap.set(n.id, n);
		}

		if (!selectedPhase) {
			// "All" mode — reset everything to full opacity, preserve per-node stroke color
			nodeGroup
				.select("circle")
				.transition()
				.duration(TRANSITION_MS)
				.attr("opacity", 1)
				.attr("stroke", (d) => getNodeStroke(colorOverridesRef.current?.get(d.id) ?? d.color))
				.attr("stroke-width", 1.5);

			nodeGroup
				.select("text")
				.transition()
				.duration(TRANSITION_MS)
				.attr("opacity", 1);

			linkGroup
				.transition()
				.duration(TRANSITION_MS)
				.attr("stroke", "#999")
				.attr("stroke-opacity", 0.6)
				.attr("stroke-width", 1.5);
		} else {
			// Phase mode — highlight active, dim inactive
			nodeGroup.each(function (d) {
				const el = d3.select(this);
				const active = isNodeActiveForPhase(d, selectedPhase);

				el.select("circle")
					.transition()
					.duration(TRANSITION_MS)
					.attr("opacity", active ? 1 : 0.15)
					.attr("stroke", active ? "#fff" : "#777")
					.attr("stroke-width", active ? 1.5 : 1);

				el.select("text")
					.transition()
					.duration(TRANSITION_MS)
					.attr("opacity", active ? 1 : 0.15);
			});

			linkGroup.each(function (d) {
				const el = d3.select(this);
				const active = isEdgeActiveForPhase(d, selectedPhase, nodeMap);

				el.transition()
					.duration(TRANSITION_MS)
					.attr("stroke", active ? "#999" : "#ddd")
					.attr("stroke-opacity", active ? 0.6 : 0.08)
					.attr("stroke-width", active ? 1.5 : 1);
			});
		}
	}, [selectedPhase, nodes, isNodeActiveForPhase, isEdgeActiveForPhase]);

	return (
		<div ref={containerRef} className="w-full h-full">
			<svg
				ref={svgRef}
				className="w-full h-full"
				style={{ background: "#fafafa" }}
			/>
		</div>
	);
};
