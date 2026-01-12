/**
 * Cloudflare Worker for Domain Config Sync
 * 
 * This worker receives domain change notifications from CloudStream providers
 * and updates the config files in the GitHub repository.
 * 
 * ENVIRONMENT VARIABLES (set in Cloudflare Dashboard → Workers → Settings → Variables):
 *   - GITHUB_TOKEN: Your GitHub Personal Access Token with 'repo' scope
 *   - GITHUB_OWNER: alyabroudy1
 *   - GITHUB_REPO: omarC
 */

export default {
    async fetch(request, env) {
        // Handle CORS preflight
        if (request.method === 'OPTIONS') {
            return new Response(null, {
                headers: {
                    'Access-Control-Allow-Origin': '*',
                    'Access-Control-Allow-Methods': 'POST, OPTIONS',
                    'Access-Control-Allow-Headers': 'Content-Type',
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
