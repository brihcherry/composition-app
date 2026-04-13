// Transforms raw GraphTimePlaySheet data into processed nodes/edges
// with time dimension, ready for D3 rendering.

import type { LegendEntry } from "@/types/graph";
import type {
	RawTimeGraphData,
	TimeNode,
	TimeEdge,
	TimeHash,
	TimeGraphDataResult,
} from "@/types/timeGraph";

/** Canonical phase order for the SDLC timeline. */
const PHASE_ORDER = ["Requirements", "Design", "Develop", "Test", "Decommissioned"];

/** Extract a clean string from a propHash value, stripping wrapping quotes. */
function cleanPropString(value: unknown): string {
	if (value == null) return "";
	const str = String(value);
	if (str.startsWith('"') && str.endsWith('"')) {
		return str.slice(1, -1);
	}
	return str;
}

/** Convert VERTEX_COLOR_PROPERTY "R,G,B" string to CSS rgb(). */
function rgbString(raw: unknown): string {
	const str = cleanPropString(raw);
	const parts = str.split(",").map((s) => s.trim());
	if (parts.length === 3 && parts.every((p) => !isNaN(Number(p)))) {
		return `rgb(${parts[0]},${parts[1]},${parts[2]})`;
	}
	return "rgb(120,120,120)";
}

/** Canonical colors by node type (override JSON data values). */
const TYPE_COLOR_OVERRIDES: Record<string, string> = {
	SystemInterface: "#7C3AED", // violet-600 — purple
};

/** Process raw time graph data into structures ready for rendering. */
export function getTimeGraphData(data: RawTimeGraphData): TimeGraphDataResult {
	const nodeMap = new Map<string, TimeNode>();
	const typeCounts = new Map<string, { color: string; count: number }>();
	const phaseSet = new Set<string>();

	for (const [uri, rawNode] of Object.entries(data.nodes)) {
		const ph = rawNode.propHash;
		const type = cleanPropString(ph.VERTEX_TYPE_PROPERTY) || "Unknown";
		const color = TYPE_COLOR_OVERRIDES[type] ?? rgbString(ph.VERTEX_COLOR_PROPERTY);
		const label =
			cleanPropString(ph.VERTEX_LABEL_PROPERTY) ||
			uri.split("/").pop() ||
			uri;

		const timeHash: TimeHash | null = ph.timeHash ?? null;
		if (timeHash) {
			for (const phaseName of Object.keys(timeHash)) {
				phaseSet.add(phaseName);
			}
		}

		const node: TimeNode = {
			id: uri,
			label,
			type,
			color,
			fullName: cleanPropString(ph.PhysicalName).replace(/_/g, " "),
			description: "",
			connectionCount: 0,
			propHash: ph,
			timeHash,
			hasTimeData: timeHash !== null,
		};

		nodeMap.set(uri, node);

		const existing = typeCounts.get(type);
		if (existing) {
			existing.count++;
		} else {
			typeCounts.set(type, { color, count: 1 });
		}
	}

	// Process edges — only include edges where both source and target exist
	const edges: TimeEdge[] = [];
	for (const rawEdge of data.edges) {
		const sourceNode = nodeMap.get(rawEdge.source);
		const targetNode = nodeMap.get(rawEdge.target);
		if (!sourceNode || !targetNode) continue;

		sourceNode.connectionCount++;
		targetNode.connectionCount++;

		const ph = rawEdge.propHash;
		edges.push({
			id: rawEdge.uri,
			source: sourceNode,
			target: targetNode,
			sourceId: rawEdge.source,
			targetId: rawEdge.target,
			edgeType: cleanPropString(ph.EDGE_TYPE) || "Unknown",
			edgeName: cleanPropString(ph.EDGE_NAME) || "",
			data: cleanPropString(ph.Data) || "",
			format: cleanPropString(ph.Format) || "",
			protocol: cleanPropString(ph.Protocol) || "",
			frequency: cleanPropString(ph.Frequency) || "",
			interfaceName: cleanPropString(ph.Interface_Name) || "",
		});
	}

	const nodes = Array.from(nodeMap.values());

	// Build legend sorted by count descending
	const legend: LegendEntry[] = Array.from(typeCounts.entries())
		.map(([type, { color, count }]) => ({ type, color, count }))
		.sort((a, b) => b.count - a.count);

	// Order phases according to canonical SDLC order, with unknowns appended
	const phases = PHASE_ORDER.filter((p) => phaseSet.has(p));
	for (const p of phaseSet) {
		if (!phases.includes(p)) phases.push(p);
	}

	return {
		nodes,
		edges,
		legend,
		phases,
		title: data.title || "How does the composition of this system change over time?",
	};
}
