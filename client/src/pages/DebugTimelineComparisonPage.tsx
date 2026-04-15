// DebugTimelineComparisonPage.tsx
//
// Debug page that compares legacy insight #21 (GraphTimePlaySheet) against the new
// GetCompositionTimelineReactor for every active system.
//
// Three comparison layers are shown per system:
//   1. Structural nodes  — URI set diff between legacy and new
//   2. Structural edges  — source|target key set diff
//   3. TimeHash overlay  — which nodes carry phase overlays, and which phase keys
//
// Navigate to /#/debug-timeline-comparison to use this page.
// Delete this file once output parity is confirmed.

import { useCallback, useEffect, useRef, useState } from "react";
import { runPixel } from "@semoss/sdk";
import { useInsight } from "@semoss/sdk/react";

// ── Types ─────────────────────────────────────────────────────────────────────

interface SystemOption {
	uri: string;
	label: string;
}

interface TimeHashPhaseDiff {
	legacyPhases: string[];
	newPhases: string[];
	phasesOnlyInLegacy: string[];
	phasesOnlyInNew: string[];
}

interface TimeHashValueDiff {
	legacy: Record<string, string>;
	new: Record<string, string>;
}

interface ComparisonResult {
	system: string;
	systemUri: string;
	match: boolean;
	structuralMatch?: boolean;
	timeHashMatch?: boolean;
	error?: string;
	legacy?: { nodes: number; edges: number; timeHashNodes: number };
	new?: { nodes: number; edges: number; timeHashNodes: number };
	// Structural diffs
	nodesOnlyInLegacy?: string[];
	nodesOnlyInNew?: string[];
	edgesOnlyInLegacy?: string[];
	edgesOnlyInNew?: string[];
	// TimeHash / overlay diffs
	timeHashOnlyInLegacy?: string[];
	timeHashOnlyInNew?: string[];
	timeHashPhaseMismatch?: string[];
	timeHashPhaseDiffs?: Record<string, TimeHashPhaseDiff>;
	timeHashValueMismatch?: string[];
	timeHashValueDiffs?: Record<string, Record<string, TimeHashValueDiff>>;
}

interface PixelCallRecord {
	id: string;
	label: string;
	pixel: string;
	systemUri?: string;
	systemLabel?: string;
	runAt: string;
	response?: unknown;
	errors?: string[];
	extractedOutput?: unknown;
}

type Status = "idle" | "loading-list" | "running" | "done";
type FilterMode = "all" | "failures" | "mismatches";

// ── Main page ─────────────────────────────────────────────────────────────────

export const DebugTimelineComparisonPage = () => {
	const { insightId } = useInsight();
	const [status, setStatus] = useState<Status>("idle");
	const [systems, setSystems] = useState<SystemOption[]>([]);
	const [results, setResults] = useState<ComparisonResult[]>([]);
	const [pixelCalls, setPixelCalls] = useState<PixelCallRecord[]>([]);
	const [currentIndex, setCurrentIndex] = useState(-1);
	const [loadError, setLoadError] = useState<string | null>(null);
	const [filterMode, setFilterMode] = useState<FilterMode>("all");
	const [search, setSearch] = useState("");
	const cancelledRef = useRef(false);

	const appendPixelCall = useCallback((record: PixelCallRecord) => {
		setPixelCalls((prev) => [...prev, record]);
	}, []);

	const exportPixelCalls = useCallback(() => {
		const payload = {
			exportedAt: new Date().toISOString(),
			insightId,
			summary: {
				totalSystems: systems.length,
				totalResults: results.length,
				totalPixelCalls: pixelCalls.length,
			},
			systems,
			results,
			pixelCalls,
		};

		const blob = new Blob([JSON.stringify(payload, null, 2)], {
			type: "application/json",
		});
		const url = URL.createObjectURL(blob);
		const anchor = document.createElement("a");
		const timestamp = new Date().toISOString().replace(/[.:]/g, "-");
		anchor.href = url;
		anchor.download = `debug-timeline-comparison-${timestamp}.json`;
		document.body.appendChild(anchor);
		anchor.click();
		anchor.remove();
		URL.revokeObjectURL(url);
	}, [insightId, pixelCalls, results, systems]);

	// ── Load system list ────────────────────────────────────────────────────
	const loadSystems = useCallback(async () => {
		if (!insightId) return;
		setStatus("loading-list");
		setLoadError(null);
		try {
			const pixel = "ListActiveSystems();";
			const response = await runPixel(pixel, insightId);
			appendPixelCall({
				id: `list-active-systems-${Date.now()}`,
				label: "ListActiveSystems",
				pixel,
				runAt: new Date().toISOString(),
				response,
				errors: response.errors,
				extractedOutput: response.pixelReturn[0]?.output,
			});
			if (response.errors.length > 0) {
				setLoadError(response.errors.join(", "));
				setStatus("idle");
				return;
			}
			const output = response.pixelReturn[0]?.output;
			if (Array.isArray(output)) setSystems(output as SystemOption[]);
			setStatus("idle");
		} catch (err) {
			setLoadError(
				err instanceof Error ? err.message : "Failed to load systems",
			);
			setStatus("idle");
		}
	}, [appendPixelCall, insightId]);

	useEffect(() => {
		loadSystems();
	}, [loadSystems]);

	// ── Run all sequentially ───────────────────────────────────────────────
	const runAll = useCallback(async () => {
		if (!insightId || systems.length === 0) return;
		cancelledRef.current = false;
		setStatus("running");
		setResults([]);
		setPixelCalls((prev) =>
			prev.filter((call) => call.label === "ListActiveSystems"),
		);
		setCurrentIndex(0);

		const allResults: ComparisonResult[] = [];

		for (let i = 0; i < systems.length; i++) {
			if (cancelledRef.current) break;
			setCurrentIndex(i);
			const sys = systems[i];
			try {
				const pixel = `CompareCompositionTimeline(systemUri=["${sys.uri}"]);`;
				const response = await runPixel(pixel, insightId);
				appendPixelCall({
					id: `compare-${sys.uri}-${Date.now()}`,
					label: "CompareCompositionTimeline",
					pixel,
					systemUri: sys.uri,
					systemLabel: sys.label,
					runAt: new Date().toISOString(),
					response,
					errors: response.errors,
					extractedOutput: response.pixelReturn[0]?.output,
				});
				if (response.errors.length > 0) {
					allResults.push({
						system: sys.label,
						systemUri: sys.uri,
						match: false,
						error: response.errors.join(", "),
					});
				} else {
					allResults.push(
						response.pixelReturn[0]?.output as ComparisonResult,
					);
				}
			} catch (err) {
				allResults.push({
					system: sys.label,
					systemUri: sys.uri,
					match: false,
					error:
						err instanceof Error ? err.message : "Unknown error",
				});
			}
			setResults([...allResults]);
		}

		setStatus("done");
	}, [appendPixelCall, insightId, systems]);

	const cancel = useCallback(() => {
		cancelledRef.current = true;
	}, []);

	// ── Run single system ──────────────────────────────────────────────────
	const runOne = useCallback(
		async (sys: SystemOption) => {
			if (!insightId) return;
			setStatus("running");
			setCurrentIndex(systems.indexOf(sys));
			try {
				const pixel = `CompareCompositionTimeline(systemUri=["${sys.uri}"]);`;
				const response = await runPixel(pixel, insightId);
				appendPixelCall({
					id: `compare-${sys.uri}-${Date.now()}`,
					label: "CompareCompositionTimeline",
					pixel,
					systemUri: sys.uri,
					systemLabel: sys.label,
					runAt: new Date().toISOString(),
					response,
					errors: response.errors,
					extractedOutput: response.pixelReturn[0]?.output,
				});
				let result: ComparisonResult;
				if (response.errors.length > 0) {
					result = {
						system: sys.label,
						systemUri: sys.uri,
						match: false,
						error: response.errors.join(", "),
					};
				} else {
					result = response.pixelReturn[0]?.output as ComparisonResult;
				}
				setResults((prev) => {
					const copy = [...prev];
					const idx = copy.findIndex(
						(r) => r.systemUri === sys.uri,
					);
					idx >= 0 ? (copy[idx] = result) : copy.push(result);
					return copy;
				});
			} catch (err) {
				setResults((prev) => [
					...prev,
					{
						system: sys.label,
						systemUri: sys.uri,
						match: false,
						error:
							err instanceof Error
								? err.message
								: "Unknown error",
					},
				]);
			}
			setStatus("done");
		},
		[appendPixelCall, insightId, systems],
	);

	// ── Summary stats ──────────────────────────────────────────────────────
	const passed = results.filter((r) => r.match).length;
	const overlayDiff = results.filter(
		(r) => !r.match && r.structuralMatch && !r.timeHashMatch && !r.error,
	).length;
	const structural = results.filter(
		(r) => !r.match && !r.structuralMatch && !r.error,
	).length;
	const errored = results.filter((r) => !!r.error).length;
	const pct =
		results.length > 0
			? Math.round((passed / results.length) * 100)
			: null;

	// ── Filtered + searched display list ──────────────────────────────────
	const displayedSystems = systems.filter((sys) => {
		const r = results.find((res) => res.systemUri === sys.uri);
		const matchesFilter =
			filterMode === "all" ||
			(filterMode === "failures" && (!r || !r.match)) ||
			(filterMode === "mismatches" && r && !r.match && !r.error);
		const matchesSearch =
			search.trim() === "" ||
			sys.label.toLowerCase().includes(search.toLowerCase());
		return matchesFilter && matchesSearch;
	});

	return (
		<div className="flex flex-col h-full bg-gray-50 min-h-0">
			{/* ── Header ─────────────────────────────────────────────────── */}
			<header className="shrink-0 border-b border-gray-200 bg-white px-6 py-4 space-y-3">
				{/* Title row */}
				<div className="flex items-start justify-between gap-4">
					<div>
						<h1 className="text-lg font-semibold text-gray-900">
							Debug: Composition Timeline
						</h1>
						<p className="text-sm text-gray-500 mt-0.5">
							Legacy insight&nbsp;#21 (GraphTimePlaySheet) vs
							&nbsp;
							<code className="text-xs bg-gray-100 px-1 rounded">
								GetCompositionTimelineReactor
							</code>
						</p>
					</div>
					<div className="flex gap-2 shrink-0">
						<button
							type="button"
							onClick={exportPixelCalls}
							disabled={pixelCalls.length === 0}
							className="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
						>
							Export JSON{pixelCalls.length > 0 ? ` (${pixelCalls.length})` : ""}
						</button>
						{status === "running" ? (
							<button
								type="button"
								onClick={cancel}
								className="px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-lg hover:bg-red-700 transition-colors"
							>
								Cancel
							</button>
						) : (
							<button
								type="button"
								onClick={runAll}
								disabled={
									systems.length === 0 ||
									status === "loading-list"
								}
								className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
							>
								{status === "loading-list"
									? "Loading systems…"
									: `Run All (${systems.length})`}
							</button>
						)}
					</div>
				</div>

				{/* Summary stats */}
				{results.length > 0 && (
					<div className="flex items-center gap-4 text-sm">
						<span className="text-green-700 font-medium">
							{passed} passed
						</span>
						{overlayDiff > 0 && (
							<span className="text-yellow-600 font-medium">
								{overlayDiff} overlay diff
							</span>
						)}
						{structural > 0 && (
							<span className="text-red-600 font-medium">
								{structural} structural mismatch
							</span>
						)}
						{errored > 0 && (
							<span className="text-gray-500">
								{errored} error
							</span>
						)}
						<span className="text-gray-400 text-xs ml-auto">
							{pixelCalls.length} pixel calls captured &middot; 
							{results.length}/{systems.length} run
							{pct !== null && <> &middot; {pct}% pass rate</>}
						</span>
					</div>
				)}

				{/* Progress bar */}
				{status === "running" && (
					<div>
						<div className="flex justify-between text-xs text-gray-400 mb-1">
							<span>
								{systems[currentIndex]?.label ?? "…"}
							</span>
							<span>
								{currentIndex + 1}&nbsp;/&nbsp;{systems.length}
							</span>
						</div>
						<div className="h-1.5 w-full rounded-full bg-gray-200">
							<div
								className="h-1.5 rounded-full bg-blue-500 transition-all duration-200"
								style={{
									width: `${((currentIndex + 1) / systems.length) * 100}%`,
								}}
							/>
						</div>
					</div>
				)}

				{/* Filter + search row */}
				<div className="flex items-center gap-3 flex-wrap">
					<div className="flex gap-1">
						{(
							[
								["all", "All"],
								["failures", "Failures"],
								["mismatches", "Mismatches"],
							] as [FilterMode, string][]
						).map(([mode, label]) => (
							<button
								key={mode}
								type="button"
								onClick={() => setFilterMode(mode)}
								className={`px-3 py-1 text-xs rounded-full border transition-colors ${
									filterMode === mode
										? "bg-blue-600 text-white border-blue-600"
										: "bg-white text-gray-600 border-gray-300 hover:border-blue-400"
								}`}
							>
								{label}
							</button>
						))}
					</div>
					<input
						type="text"
						placeholder="Search systems…"
						value={search}
						onChange={(e) => setSearch(e.target.value)}
						className="ml-auto text-xs border border-gray-300 rounded px-3 py-1 w-48 focus:outline-none focus:ring-1 focus:ring-blue-400"
					/>
				</div>
			</header>

			{/* Error banner */}
			{loadError && (
				<div className="mx-6 mt-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 shrink-0">
					{loadError}
				</div>
			)}

			{/* Legend */}
			<div className="px-6 pt-3 pb-1 flex gap-5 text-xs text-gray-500 shrink-0 flex-wrap">
				<span className="flex items-center gap-1.5">
					<Dot color="green" /> Full match (structure + overlays)
				</span>
				<span className="flex items-center gap-1.5">
					<Dot color="yellow" /> Structural match, overlay diff
				</span>
				<span className="flex items-center gap-1.5">
					<Dot color="red" /> Structural mismatch
				</span>
				<span className="flex items-center gap-1.5">
					<Dot color="gray" /> Error / not run
				</span>
				<span className="flex items-center gap-1.5 ml-auto text-gray-400">
					L&nbsp;= Legacy &nbsp;|&nbsp; N&nbsp;= New &nbsp;|&nbsp;
					TH&nbsp;= TimeHash nodes
				</span>
			</div>

			{/* Table */}
			<div className="flex-1 overflow-auto px-6 py-2">
				{displayedSystems.length === 0 && status !== "running" ? (
					<div className="text-center py-16 text-gray-400 text-sm">
						{systems.length === 0
							? "Loading active systems…"
							: "No results match the current filter."}
					</div>
				) : (
					<table className="w-full text-sm border-collapse">
						<thead className="sticky top-0 bg-gray-50 z-10">
							<tr className="text-left text-xs text-gray-500 uppercase tracking-wider border-b-2 border-gray-300">
								<th className="pb-2 pr-3 w-8">#</th>
								<th className="pb-2 pr-4">System</th>
								<th className="pb-2 pr-3 w-28">Status</th>
								<th className="pb-2 pr-2 text-right w-14">
									L&nbsp;Nodes
								</th>
								<th className="pb-2 pr-2 text-right w-14">
									N&nbsp;Nodes
								</th>
								<th className="pb-2 pr-2 text-right w-14">
									L&nbsp;Edges
								</th>
								<th className="pb-2 pr-2 text-right w-14">
									N&nbsp;Edges
								</th>
								<th className="pb-2 pr-2 text-right w-12">
									L&nbsp;TH
								</th>
								<th className="pb-2 pr-3 text-right w-12">
									N&nbsp;TH
								</th>
								<th className="pb-2 w-16">Details</th>
							</tr>
						</thead>
						<tbody>
							{displayedSystems.map((sys) => {
								const realIdx = systems.indexOf(sys);
								const r = results.find(
									(res) => res.systemUri === sys.uri,
								);
								const isRunning =
									status === "running" &&
									realIdx === currentIndex;
								return (
									<ResultRow
										key={sys.uri}
										index={realIdx}
										system={sys}
										result={r}
										isRunning={isRunning}
										onRunOne={() => runOne(sys)}
									/>
								);
							})}
						</tbody>
					</table>
				)}
			</div>
		</div>
	);
};

// ── ResultRow ─────────────────────────────────────────────────────────────────

function ResultRow({
	index,
	system,
	result,
	isRunning,
	onRunOne,
}: {
	index: number;
	system: SystemOption;
	result?: ComparisonResult;
	isRunning: boolean;
	onRunOne: () => void;
}) {
	const [expanded, setExpanded] = useState(false);

	const dotEl = () => {
		if (isRunning) return <Dot color="blue" pulse />;
		if (!result) return <Dot color="gray" />;
		if (result.error) return <Dot color="gray" />;
		if (result.match) return <Dot color="green" />;
		if (result.structuralMatch) return <Dot color="yellow" />;
		return <Dot color="red" />;
	};

	const statusLabel = () => {
		if (isRunning)
			return (
				<span className="text-blue-600 text-xs font-medium">
					Running…
				</span>
			);
		if (!result)
			return (
				<button
					type="button"
					onClick={onRunOne}
					className="text-blue-600 hover:text-blue-800 underline text-xs"
				>
					Run
				</button>
			);
		if (result.error)
			return (
				<span className="text-gray-500 text-xs">Error</span>
			);
		if (result.match)
			return (
				<span className="text-green-700 text-xs font-semibold">
					Match
				</span>
			);
		if (result.structuralMatch)
			return (
				<span className="text-yellow-600 text-xs font-semibold">
					Overlay diff
				</span>
			);
		return (
			<span className="text-red-600 text-xs font-semibold">
				Mismatch
			</span>
		);
	};

	const rowBg = isRunning
		? "bg-blue-50"
		: !result
			? ""
			: result.error
				? "bg-gray-50"
				: result.match
					? ""
					: result.structuralMatch
						? "bg-yellow-50"
						: "bg-red-50";

	const hasDetails = !!result;
	const nodeDelta =
		result?.legacy && result?.new
			? result.new.nodes - result.legacy.nodes
			: null;
	const edgeDelta =
		result?.legacy && result?.new
			? result.new.edges - result.legacy.edges
			: null;
	const thDelta =
		result?.legacy && result?.new
			? result.new.timeHashNodes - result.legacy.timeHashNodes
			: null;

	return (
		<>
			<tr
				className={`border-t border-gray-200 hover:bg-gray-50/60 transition-colors text-xs ${rowBg}`}
			>
				<td className="py-2 pr-3 text-gray-400 tabular-nums">
					{index + 1}
				</td>
				<td className="py-2 pr-4 font-medium text-gray-900">
					<div className="flex items-center gap-2 min-w-0">
						{dotEl()}
						<span className="truncate max-w-xs">{system.label}</span>
					</div>
				</td>
				<td className="py-2 pr-3">{statusLabel()}</td>

				{/* Nodes */}
				<td className="py-2 pr-2 tabular-nums text-right text-gray-500">
					{result?.legacy?.nodes ?? "—"}
				</td>
				<td
					className={`py-2 pr-2 tabular-nums text-right ${
						nodeDelta !== null && nodeDelta !== 0
							? "text-red-600 font-semibold"
							: "text-gray-500"
					}`}
				>
					{result?.new?.nodes ?? "—"}
					{nodeDelta !== null && nodeDelta !== 0 && (
						<span className="text-xs ml-0.5 opacity-70">
							({nodeDelta > 0 ? "+" : ""}
							{nodeDelta})
						</span>
					)}
				</td>

				{/* Edges */}
				<td className="py-2 pr-2 tabular-nums text-right text-gray-500">
					{result?.legacy?.edges ?? "—"}
				</td>
				<td
					className={`py-2 pr-2 tabular-nums text-right ${
						edgeDelta !== null && edgeDelta !== 0
							? "text-red-600 font-semibold"
							: "text-gray-500"
					}`}
				>
					{result?.new?.edges ?? "—"}
					{edgeDelta !== null && edgeDelta !== 0 && (
						<span className="text-xs ml-0.5 opacity-70">
							({edgeDelta > 0 ? "+" : ""}
							{edgeDelta})
						</span>
					)}
				</td>

				{/* TimeHash nodes */}
				<td className="py-2 pr-2 tabular-nums text-right text-gray-500">
					{result?.legacy?.timeHashNodes ?? "—"}
				</td>
				<td
					className={`py-2 pr-3 tabular-nums text-right ${
						thDelta !== null && thDelta !== 0
							? "text-yellow-600 font-semibold"
							: "text-gray-500"
					}`}
				>
					{result?.new?.timeHashNodes ?? "—"}
					{thDelta !== null && thDelta !== 0 && (
						<span className="text-xs ml-0.5 opacity-70">
							({thDelta > 0 ? "+" : ""}
							{thDelta})
						</span>
					)}
				</td>

				<td className="py-2">
					{hasDetails && (
						<button
							type="button"
							onClick={() => setExpanded((e) => !e)}
							className="text-blue-600 hover:text-blue-800 text-xs"
						>
							{expanded ? "Hide" : "Details"}
						</button>
					)}
				</td>
			</tr>

			{expanded && result && (
				<tr>
					<td
						colSpan={10}
						className="px-8 py-4 bg-white border-t border-gray-100"
					>
						<DiffDetails result={result} />
					</td>
				</tr>
			)}
		</>
	);
}

// ── DiffDetails ───────────────────────────────────────────────────────────────

function DiffDetails({ result }: { result: ComparisonResult }) {
	if (result.error) {
		return (
			<div className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">
				<p className="font-semibold mb-1">Error</p>
				<p className="font-mono whitespace-pre-wrap break-all">
					{result.error}
				</p>
			</div>
		);
	}

	const hasPhaseDiffs =
		result.timeHashPhaseDiffs &&
		Object.keys(result.timeHashPhaseDiffs).length > 0;
	const hasValueDiffs =
		result.timeHashValueDiffs &&
		Object.keys(result.timeHashValueDiffs).length > 0;

	return (
		<div className="space-y-3">
			{/* Summary badges */}
			<div className="flex gap-6 text-xs text-gray-500 border-b border-gray-100 pb-2">
				<span>
					Structural:{" "}
					<strong
						className={
							result.structuralMatch
								? "text-green-700"
								: "text-red-600"
						}
					>
						{result.structuralMatch ? "✓ Match" : "✗ Mismatch"}
					</strong>
				</span>
				<span>
					Overlays:{" "}
					<strong
						className={
							result.timeHashMatch
								? "text-green-700"
								: "text-yellow-700"
						}
					>
						{result.timeHashMatch ? "✓ Match" : "✗ Diff"}
					</strong>
				</span>
				<span className="text-gray-400">
					Legacy: {result.legacy?.nodes} nodes /{" "}
					{result.legacy?.edges} edges /{" "}
					{result.legacy?.timeHashNodes} overlay nodes
				</span>
				<span className="text-gray-400">
					New: {result.new?.nodes} nodes / {result.new?.edges}{" "}
					edges / {result.new?.timeHashNodes} overlay nodes
				</span>
			</div>

			{/* Structural diffs grid */}
			<div className="grid grid-cols-2 gap-3">
				<DiffSection
					label="Nodes only in Legacy (missing from new)"
					items={result.nodesOnlyInLegacy ?? []}
					variant="red"
				/>
				<DiffSection
					label="Nodes only in New (extra in new)"
					items={result.nodesOnlyInNew ?? []}
					variant="blue"
				/>
				<DiffSection
					label="Edges only in Legacy (missing from new)"
					items={result.edgesOnlyInLegacy ?? []}
					variant="red"
				/>
				<DiffSection
					label="Edges only in New (extra in new)"
					items={result.edgesOnlyInNew ?? []}
					variant="blue"
				/>
			</div>

			{/* Overlay diffs grid */}
			<div className="grid grid-cols-2 gap-3">
				<DiffSection
					label="Overlay only in Legacy (phase missing from new node)"
					items={result.timeHashOnlyInLegacy ?? []}
					variant="yellow"
				/>
				<DiffSection
					label="Overlay only in New (extra phase on new node)"
					items={result.timeHashOnlyInNew ?? []}
					variant="neutral"
				/>
			</div>

			{/* Phase-key mismatches */}
			{hasPhaseDiffs && (
				<div className="rounded border border-yellow-200 bg-yellow-50 p-3">
					<p className="text-xs font-semibold text-yellow-700 mb-2">
						Phase key mismatches (
						{result.timeHashPhaseMismatch?.length ?? 0} nodes)
					</p>
					<div className="space-y-2 max-h-56 overflow-y-auto">
						{Object.entries(result.timeHashPhaseDiffs!).map(
							([nodeName, diff]) => (
								<div
									key={nodeName}
									className="text-xs bg-white rounded border border-yellow-100 p-2"
								>
									<p className="font-semibold text-gray-700 mb-1 font-mono">
										{nodeName}
									</p>
									<div className="flex gap-6 flex-wrap">
										<span className="text-gray-400">
											Legacy:{" "}
											{diff.legacyPhases.join(", ")}
										</span>
										<span className="text-gray-400">
											New: {diff.newPhases.join(", ")}
										</span>
										{diff.phasesOnlyInLegacy.length >
											0 && (
											<span className="text-red-600">
												Only in legacy:{" "}
												{diff.phasesOnlyInLegacy.join(
													", ",
												)}
											</span>
										)}
										{diff.phasesOnlyInNew.length > 0 && (
											<span className="text-blue-600">
												Only in new:{" "}
												{diff.phasesOnlyInNew.join(
													", ",
												)}
											</span>
										)}
									</div>
								</div>
							),
						)}
					</div>
				</div>
			)}

			{/* Phase-value mismatches */}
			{hasValueDiffs && (
				<div className="rounded border border-amber-200 bg-amber-50 p-3">
					<p className="text-xs font-semibold text-amber-700 mb-2">
						Phase value mismatches (
						{result.timeHashValueMismatch?.length ?? 0} nodes)
					</p>
					<div className="space-y-2 max-h-56 overflow-y-auto">
						{Object.entries(result.timeHashValueDiffs!).map(
							([nodeName, perPhase]) => (
								<div
									key={nodeName}
									className="text-xs bg-white rounded border border-amber-100 p-2"
								>
									<p className="font-semibold text-gray-700 mb-1 font-mono">
										{nodeName}
									</p>
									<div className="space-y-1">
										{Object.entries(perPhase).map(([phase, diff]) => (
											<div key={phase} className="rounded border border-gray-200 p-1.5">
												<p className="font-semibold text-gray-600 mb-1">{phase}</p>
												<div className="grid grid-cols-2 gap-2">
													<div>
														<p className="text-gray-400">Legacy</p>
														<pre className="whitespace-pre-wrap break-all text-[10px] text-gray-600">{JSON.stringify(diff.legacy, null, 2)}</pre>
													</div>
													<div>
														<p className="text-gray-400">New</p>
														<pre className="whitespace-pre-wrap break-all text-[10px] text-gray-600">{JSON.stringify(diff.new, null, 2)}</pre>
													</div>
												</div>
											</div>
										))}
									</div>
								</div>
							),
						)}
					</div>
				</div>
			)}

			{result.match && (
				<p className="text-xs text-green-700 font-medium">
					All checks passed — full structural and overlay match.
				</p>
			)}
		</div>
	);
}

// ── DiffSection ───────────────────────────────────────────────────────────────

type DiffVariant = "red" | "blue" | "yellow" | "neutral";

const VARIANT_STYLES: Record<DiffVariant, string> = {
	red: "text-red-700 bg-red-50 border-red-200",
	blue: "text-blue-700 bg-blue-50 border-blue-200",
	yellow: "text-yellow-700 bg-yellow-50 border-yellow-200",
	neutral: "text-gray-600 bg-gray-50 border-gray-200",
};

function DiffSection({
	label,
	items,
	variant = "neutral",
}: {
	label: string;
	items: string[];
	variant?: DiffVariant;
}) {
	if (items.length === 0) return null;
	return (
		<div className={`rounded border p-3 ${VARIANT_STYLES[variant]}`}>
			<p className="text-xs font-semibold mb-1.5">
				{label} ({items.length})
			</p>
			<ul className="text-xs space-y-0.5 max-h-36 overflow-y-auto">
				{items.map((item) => {
					const local = item.includes("/")
						? item.split("/").pop()
						: item;
					return (
						<li key={item} className="font-mono" title={item}>
							{local}
						</li>
					);
				})}
			</ul>
		</div>
	);
}

// ── Dot helper ────────────────────────────────────────────────────────────────

const DOT_COLORS: Record<string, string> = {
	green: "bg-green-500",
	yellow: "bg-yellow-400",
	red: "bg-red-500",
	blue: "bg-blue-400",
	gray: "bg-gray-300",
};

function Dot({
	color,
	pulse = false,
}: {
	color: keyof typeof DOT_COLORS;
	pulse?: boolean;
}) {
	return (
		<span
			className={`inline-block w-2.5 h-2.5 rounded-full shrink-0 ${DOT_COLORS[color]} ${pulse ? "animate-pulse" : ""}`}
		/>
	);
}
