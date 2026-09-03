/**
 * Cloudflare Worker for Domain Config Sync
 */

/**
 * Allowlist of provider config file names that may be written.
 *
 * MUST be updated whenever a file is added to / removed from `configs/*.json`
 * in the repository, otherwise the new provider's domain sync will 400.
 */
const KNOWN_CONFIG_FILES = new Set([
    'akwam.json',
    'anim3rb.json',
    'arabseedv4.json',
    'bristege.json',
    'cimaleek.json',
    'cimanow.json',
    'cimatn.json',
    'cimawbas.json',
    'dima-toon.json',
    'egydead.json',
    'eishk.json',
    'eseek.json',
    'faselhd.json',
    'kooralive.json',
    'krmzy.json',
    'laroza.json',
    'mycima.json',
    'mycimaclone.json',
    'syrialive.json',
    'tuktukhd.json',
    'wecima.json',
    'yallashoot.json',
]);

export default {
    async fetch(request, env) {
        // Handle CORS preflight
        if (request.method === 'OPTIONS') {
            return new Response(null, {
                headers: {
                    'Access-Control-Allow-Origin': '*',
                    'Access-Control-Allow-Methods': 'POST, OPTIONS',
                    'Access-Control-Allow-Headers': 'Content-Type, X-Sync-Secret',
                },
            });
        }

        if (request.method !== 'POST') {
            return new Response('Method not allowed', { status: 405 });
        }

        // Optional shared secret. Enforced only when SYNC_SECRET is configured on the
        // Worker; the open-source client cannot hold a real secret, so this is a
        // server-side opt-in, not an authentication guarantee.
        if (env.SYNC_SECRET && request.headers.get('X-Sync-Secret') !== env.SYNC_SECRET) {
            return new Response(JSON.stringify({ error: 'Unauthorized' }), {
                status: 401,
                headers: { 'Content-Type': 'application/json' },
            });
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

            // Provider file-name check (NOT a domain check): the config file must be one
            // of the known names. Also closes path traversal via `configs/${configFile}`.
            if (configFile.includes('/') || configFile.includes('..') || !KNOWN_CONFIG_FILES.has(configFile)) {
                return new Response(JSON.stringify({ error: 'Unknown configFile' }), {
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
