const esbuild = require("esbuild");

const production = process.argv.includes('--production');
const watch = process.argv.includes('--watch');

/**
 * @type {import('esbuild').Plugin}
 */
const esbuildProblemMatcherPlugin = {
	name: 'esbuild-problem-matcher',

	setup(build) {
		build.onStart(() => {
			console.log('[watch] build started');
		});
		build.onEnd((result) => {
			result.errors.forEach(({ text, location }) => {
				console.error(`✘ [ERROR] ${text}`);
				console.error(`    ${location.file}:${location.line}:${location.column}:`);
			});
			console.log('[watch] build finished');
		});
	},
};

async function main() {
	const ctx = await esbuild.context({
		entryPoints: [
			'src/extension.ts'
		],
		bundle: true,
		format: 'cjs',
		minify: production,
		sourcemap: !production,
		sourcesContent: false,
		platform: 'node',
		outfile: 'dist/extension.js',
		external: ['vscode'],
		logLevel: 'silent',
		plugins: [
			esbuildProblemMatcherPlugin,
		],
	});

	// Second entry: Evolution Manager webview (runs in browser, not Node)
	const ctxWebview = await esbuild.context({
		entryPoints: ['src/evolutionManager/webview/index.ts'],
		bundle: true,
		format: 'iife',
		minify: production,
		sourcemap: !production,
		sourcesContent: false,
		platform: 'browser',
		outfile: 'dist/evolution-manager.js',
		logLevel: 'silent',
		plugins: [esbuildProblemMatcherPlugin],
	});

	// Third entry: Rewrite Rule Editor webview bundle (runs in browser)
	const ctxRewriteRuleEditorWebview = await esbuild.context({
		entryPoints: ['src/rewriteRuleEditor/webview/index.ts'],
		bundle: true,
		format: 'iife',
		minify: production,
		sourcemap: !production,
		sourcesContent: false,
		platform: 'browser',
		outfile: 'dist/rewrite-rule-editor.js',
		logLevel: 'silent',
		plugins: [esbuildProblemMatcherPlugin],
	});

	// Fourth entry: MCP stdio server (standalone Node process for Cursor, no vscode)
	const ctxMcp = await esbuild.context({
		entryPoints: ['src/assistantIntegration/mcp-stdio-entry.ts'],
		bundle: true,
		format: 'cjs',
		minify: false,
		sourcemap: !production,
		sourcesContent: false,
		platform: 'node',
		outfile: 'dist/mcp-stdio-entry.js',
		logLevel: 'silent',
		plugins: [esbuildProblemMatcherPlugin],
	});

	if (watch) {
		await ctx.watch();
		await ctxWebview.watch();
		await ctxRewriteRuleEditorWebview.watch();
		await ctxMcp.watch();
	} else {
		await ctx.rebuild();
		await ctx.dispose();
		await ctxWebview.rebuild();
		await ctxWebview.dispose();
		await ctxRewriteRuleEditorWebview.rebuild();
		await ctxRewriteRuleEditorWebview.dispose();
		try {
			await ctxMcp.rebuild();
		} catch (e) {
			console.warn('[esbuild] MCP stdio entry skipped (install @modelcontextprotocol/sdk and zod for Cursor MCP).');
		}
		await ctxMcp.dispose();
	}
}

main().catch(e => {
	console.error(e);
	process.exit(1);
});
