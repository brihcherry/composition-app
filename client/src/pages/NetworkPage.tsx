// NetworkPage.tsx — Full-screen page that renders the network of systems graph.
// Composes: NetworkGraph (D3 force graph) + GraphSidebar + GraphTooltip + GraphLegend.

import { useMemo, useState, useCallback } from "react";
import { NetworkGraph } from "@/components/NetworkGraph";
import { GraphTooltip } from "@/components/GraphTooltip";
import { GraphLegend } from "@/components/GraphLegend";
import { GraphSidebar, type AnalysisMode } from "@/components/GraphSidebar";
import { getGraphData } from "@/lib/graphData";
import { findLoops, findIslands, type HighlightSet } from "@/lib/graphAnalysis";
import type { TooltipData } from "@/types/graph";

export const NetworkPage = () => {
	const graphData = useMemo(() => getGraphData(), []);
	const [tooltip, setTooltip] = useState<TooltipData | null>(null);
	const [analysisMode, setAnalysisMode] = useState<AnalysisMode>("none");

	// Compute highlight sets lazily (only when the mode is active)
	const loopResult = useMemo(
		() => findLoops(graphData.nodes, graphData.edges),
		[graphData],
	);
	const islandResult = useMemo(
		() => findIslands(graphData.nodes, graphData.edges),
		[graphData],
	);

	const highlightSet: HighlightSet | null = useMemo(() => {
		switch (analysisMode) {
			case "loops":
				return loopResult;
			case "islands":
				return islandResult;
			default:
				return null;
		}
	}, [analysisMode, loopResult, islandResult]);

	const handleModeChange = useCallback((mode: AnalysisMode) => {
		setAnalysisMode(mode);
	}, []);

	return (
		<div className="flex flex-col h-full -m-4">
			{/* Header */}
			<header className="shrink-0 border-b border-gray-200 bg-white px-6 py-3">
				<h1 className="text-lg font-semibold text-gray-900">
					{graphData.title}
				</h1>
				<p className="text-sm text-gray-500 mt-0.5">
					{graphData.nodes.length} systems &middot;{" "}
					{graphData.edges.length} interfaces
				</p>
			</header>

			{/* Content: sidebar + graph */}
			<div className="flex-1 flex overflow-hidden">
				<GraphSidebar
					activeMode={analysisMode}
					onModeChange={handleModeChange}
					loopCount={loopResult.nodeIds.size}
					islandCount={islandResult.nodeIds.size}
				/>

				<main className="flex-1 relative overflow-hidden">
					<NetworkGraph
						nodes={graphData.nodes}
						edges={graphData.edges}
						onTooltipChange={setTooltip}
						highlightSet={highlightSet}
					/>
					<GraphLegend entries={graphData.legend} />
					<GraphTooltip tooltip={tooltip} />
				</main>
			</div>
		</div>
	);
};
