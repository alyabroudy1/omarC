/**
 * Cloudflare Worker for Domain Config Sync (with Debug)
 */

export default {
    async fetch(request, env) {
        // Handle CORS preflight
        if (request.method === 'OPTIONS') {
            return new Response(null, {
                headers: {
                    'Access-Control-Allow-Origin': '*',
                    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
                    'Access-Control-Allow-Headers': 'Content-Type',
                },
            });
        }

        // DEBUG: GET request shows environment variable status
        if (request.method === 'GET') {
            return new Response(JSON.stringify({
                debug: true,
                hasToken: !!env.GITHUB_TOKEN,
                tokenLength: env.GITHUB_TOKEN ? env.GITHUB_TOKEN.length : 0,
                tokenPrefix: env.GITHUB_TOKEN ? env.GITHUB_TOKEN.substring(0, 4) : 'MISSING',
                owner: env.GITHUB_OWNER || 'MISSING',
                repo: env.GITHUB_REPO || 'MISSING',
            }, null, 2), {
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*',
                },
            });
        }

        if (request.method !== 'POST') {
            return new Response('Method not allowed', { status: 405 });
        }

        try {
            const body = await request.json();
            const { provider, configFile, newDomain, currentVersion } = body;

            if (!provider || !configFile || !newDomain) {
                return new Response(JSON.stringify({ error: 'Missing required fields' }), {
                    status: 400,
                    headers: { 'Content-Type': 'application/json' },
                });
            }

            // Check env vars before proceeding
            if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) {
                return new Response(JSON.stringify({
                    error: 'Missing environment variables',
                    hasToken: !!env.GITHUB_TOKEN,
                    hasOwner: !!env.GITHUB_OWNER,
                    hasRepo: !!env.GITHUB_REPO,
                }), {
                    status: 500,
                    headers: { 'Content-Type': 'application/json' },
                });
            }

            // Get current file content from GitHub
            const filePath = `configs/${configFile}`;
            const getFileUrl = `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/contents/${filePath}`;

            const fileResponse = await fetch(getFileUrl, {
                headers: {
                    'Authorization': `token ${env.GITHUB_TOKEN}`,
                    'Accept': 'application/vnd.github.v3+json',
                    'User-Agent': 'CloudStream-DomainSync-Worker',
                },
            });

            if (!fileResponse.ok) {
                return new Response(JSON.stringify({
                    error: 'Failed to fetch config from GitHub',
                    details: await fileResponse.text()
                }), {
                    status: 500,
                    headers: { 'Content-Type': 'application/json' },
                });
            }

            const fileData = await fileResponse.json();
            const currentContent = JSON.parse(atob(fileData.content));

            // Check if domain actually changed
            if (currentContent.domain === newDomain) {
                return new Response(JSON.stringify({
                    message: 'Domain unchanged',
                    currentVersion: currentContent.version
                }), {
                    headers: {
                        'Content-Type': 'application/json',
                        'Access-Control-Allow-Origin': '*',
                    },
                });
            }

            // Prepare new content with incremented version
            const newVersion = currentContent.version + 1;
            const newContent = {
                domain: newDomain,
                version: newVersion,
                lastUpdated: new Date().toISOString(),
            };

            // Update file on GitHub
            const updateResponse = await fetch(getFileUrl, {
                method: 'PUT',
                headers: {
                    'Authorization': `token ${env.GITHUB_TOKEN}`,
                    'Accept': 'application/vnd.github.v3+json',
                    'User-Agent': 'CloudStream-DomainSync-Worker',
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    message: `[Auto] Update ${provider} domain to ${newDomain} (v${newVersion})`,
                    content: btoa(JSON.stringify(newContent, null, 2) + '\n'),
                    sha: fileData.sha,
                }),
            });

            if (!updateResponse.ok) {
                return new Response(JSON.stringify({
                    error: 'Failed to update config on GitHub',
                    details: await updateResponse.text()
                }), {
                    status: 500,
                    headers: { 'Content-Type': 'application/json' },
                });
            }

            return new Response(JSON.stringify({
                success: true,
                message: 'Domain updated successfully',
                newVersion: newVersion,
                newDomain: newDomain,
            }), {
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*',
                },
            });

        } catch (error) {
            return new Response(JSON.stringify({
                error: 'Internal error',
                details: error.message
            }), {
                status: 500,
                headers: { 'Content-Type': 'application/json' },
            });
        }
    },
};
