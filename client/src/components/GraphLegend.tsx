// GraphLegend.tsx — Overlay legend showing node types with color swatches and counts.

import type { LegendEntry } from "@/types/graph";

interface GraphLegendProps {
	entries: LegendEntry[];
}

export const GraphLegend = ({ entries }: GraphLegendProps) => {
	if (entries.length === 0) return null;

	return (
		<div className="absolute top-3 right-3 bg-white/90 border border-gray-200 rounded-lg px-3 py-2 shadow-md z-10">
			<div className="text-[10px] text-gray-500 font-semibold uppercase tracking-wide mb-1.5">
				Legend
			</div>
			<div className="space-y-1">
				{entries.map((entry) => (
					<div key={entry.type} className="flex items-center gap-2">
						<span
							className="inline-block w-3 h-3 rounded-full shrink-0"
							style={{ backgroundColor: entry.color }}
						/>
						<span className="text-xs text-gray-700">
							{entry.type}
						</span>
						<span className="text-xs text-gray-400 ml-auto">
							{entry.count}
						</span>
					</div>
				))}
			</div>
		</div>
	);
};
