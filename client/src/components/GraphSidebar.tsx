// GraphSidebar.tsx — Sidebar with graph analysis tool buttons.
// Provides Loop Identifier, Island Identifier, and Reset controls.

import { Button } from "@/components/ui/button";

export type AnalysisMode = "none" | "loops" | "islands";

interface GraphSidebarProps {
	activeMode: AnalysisMode;
	onModeChange: (mode: AnalysisMode) => void;
	loopCount?: number;
	islandCount?: number;
}

export const GraphSidebar = ({
	activeMode,
	onModeChange,
	loopCount,
	islandCount,
}: GraphSidebarProps) => {
	return (
		<aside className="w-64 shrink-0 border-r border-gray-200 bg-gray-50 p-4 flex flex-col gap-4 overflow-y-auto">
			<div>
				<h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
					Analysis Tools
				</h2>
			</div>

			<div className="flex flex-col gap-2">
				<Button
					variant={activeMode === "loops" ? "default" : "outline"}
					className="w-full justify-start text-left"
					onClick={() =>
						onModeChange(activeMode === "loops" ? "none" : "loops")
					}
				>
					<div>
						<div className="font-medium">Loop Identifier</div>
						<div className="text-xs opacity-70 font-normal mt-0.5">
							Highlight systems in directed cycles
						</div>
					</div>
				</Button>
				{activeMode === "loops" && loopCount !== undefined && (
					<div className="text-xs text-gray-500 px-3">
						{loopCount > 0
							? `${loopCount} node${loopCount !== 1 ? "s" : ""} in loops`
							: "No loops detected"}
					</div>
				)}

				<Button
					variant={activeMode === "islands" ? "default" : "outline"}
					className="w-full justify-start text-left"
					onClick={() =>
						onModeChange(activeMode === "islands" ? "none" : "islands")
					}
				>
					<div>
						<div className="font-medium">Island Identifier</div>
						<div className="text-xs opacity-70 font-normal mt-0.5">
							Highlight disconnected clusters
						</div>
					</div>
				</Button>
				{activeMode === "islands" && islandCount !== undefined && (
					<div className="text-xs text-gray-500 px-3">
						{islandCount > 0
							? `${islandCount} node${islandCount !== 1 ? "s" : ""} disconnected from Admissions`
							: "All nodes connected to Admissions"}
					</div>
				)}
			</div>

			<hr className="border-gray-200" />

			<Button
				variant="outline"
				className="w-full"
				onClick={() => onModeChange("none")}
				disabled={activeMode === "none"}
			>
				Reset
			</Button>
		</aside>
	);
};
