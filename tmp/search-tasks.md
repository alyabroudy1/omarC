1-lazy search problem:
- once a provider face cloudflare it return fast a placeholder movie card that once triggered it should use the httpservice provider with webview enabled to solve the cloudflare -> continue till it reachs the target provider page and return the contents to be parsed in kotlen and return the reult to that provider search row. but that then start being noisy as if we have a lot of providers  the first 5 or maybe 10 rows are all show place holder and they appear first as they are faster.
we need a mechanisem to delay result like maybe 5 sec it cloud flare appears to give a chance to provider who succeed without cloudflare to dilever their result first(the idea is not finalized, the main goal to have the real results appears first and the placeholders last, so if u have a better idea to achieve that u may suggest it)
- some providers like cimawbasProvider by layz search place holder they send: card.url=https://cloudflare.com/lazy://سيما وبس 
as a url which is totally wrong, they fail to send the real domain and they send the provider arabic name (see log3.txt for more infos)

2-normal search problem:
- pagination doesnt work. 

3-backgroud tasks not clear: 
the log keep on showing: ChromiumFetcher         com.lagradost.cloudstream3           D  [fetch.onReceivedHttpError] HTTP error on main frame | code=403
even nothing is open in the ui, evaluate whats wrong. 

4-syrialive, Koora, and YallaShoot providers are footbal providers (disable search for them)


