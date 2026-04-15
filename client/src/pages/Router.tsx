// Router.tsx - Defines all routes for the app.
//
// Uses a hash router (URLs look like /#/path) which is required for SEMOSS apps.
// All routes are wrapped in InitializedLayout, which blocks rendering until SEMOSS is ready.
//
// To add a new page:
//   1. Create a component in src/pages/
//   2. Add a route entry in the children array below
//   3. If the page is an MCP tool UI, set its path to match the resourceURI in pixel_mcp.json

import { createHashRouter, Navigate, RouterProvider } from "react-router-dom";
import { CompositionTimelinePage } from "./CompositionTimelinePage";
import { DebugComparisonPage } from "./DebugComparisonPage";
import { DebugTimelineComparisonPage } from "./DebugTimelineComparisonPage";
import { ErrorPage } from "./ErrorPage";
import { InitializedLayout } from "./layouts";

const router = createHashRouter([
	{
		// InitializedLayout waits for SEMOSS to be ready before rendering child routes
		Component: InitializedLayout,
		ErrorBoundary: ErrorPage,
		children: [
			{
				index: true,
				Component: CompositionTimelinePage,
			},
			{
				path: '/debug-comparison',
				Component: DebugTimelineComparisonPage,
			},
			{
				path: '/debug-timeline-comparison',
				Component: DebugTimelineComparisonPage,
			},
			{
				// Catch-all: redirect unknown routes to home
				path: "*",
				Component: () => <Navigate to="/" />,
			},
		],
	},
]);

export const Router = () => {
	return <RouterProvider router={router} />;
};
