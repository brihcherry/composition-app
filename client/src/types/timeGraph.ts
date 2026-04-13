// Type definitions for the Composition Over Time graph (insight #21).
// Extends the base graph types with time/phase dimension from GraphTimePlaySheet.

import type { ProcessedNode, ProcessedEdge, LegendEntry } from "./graph";

/** Per-phase data from the timeHash on a node. */
export interface PhaseData {
	phase: string;
	LOE: number;
	dependICDS: string;
	GLitem: string;
	gltag: string;
}

/** timeHash: phase name → phase properties */
export type TimeHash = Record<string, PhaseData>;

/** Raw node shape from the static JSON — propHash may contain a nested timeHash. */
export interface RawTimeNode {
	uri: string;
	propHash: Record<string, unknown> & { timeHash?: TimeHash };
}

/** Raw edge shape (same as base graph). */
export interface RawTimeEdge {
	uri: string;
	source: string;
	target: string;
	propHash: Record<string, unknown>;
}

/** Top-level shape of composition_time_data.json. */
export interface RawTimeGraphData {
	layout: string;
	nodes: Record<string, RawTimeNode>;
	edges: RawTimeEdge[];
	title: string;
	dataMakerName: string;
}

/** Processed node with optional time data. */
export interface TimeNode extends ProcessedNode {
	timeHash: TimeHash | null;
	hasTimeData: boolean;
}

/** Processed edge (no timeHash in this dataset but typed for future use). */
export interface TimeEdge extends ProcessedEdge {
	// placeholder for future per-edge time data
}

/** Result of processing the time graph data. */
export interface TimeGraphDataResult {
	nodes: TimeNode[];
	edges: TimeEdge[];
	legend: LegendEntry[];
	phases: string[];
	title: string;
}
