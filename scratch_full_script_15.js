$(document).ready(function () {
$("li[aria-label='quality'] a").each(function () {
var link = $(this).attr("href");
var match = link.match(/deva-cpmav9sk6x(\d*)\.cimanowtv\.com/);
if (match) {
var number = match[1] ? parseInt(match[1], 10) : null;
var textContent = $(this).find("p").text().trim();
var sizeMatch = textContent.match(/([\d.]+)\s*(جيجا|ميجا)/);
var fileSizeInMega = 0;
if (sizeMatch) {
var size = parseFloat(sizeMatch[1]); 
var unit = sizeMatch[2]; 
fileSizeInMega = unit === "جيجا" ? size * 1024 : size;
}
//if ((number === null || number < 39 || number > 45) && fileSizeInMega <= 512) {
//link = link.replace(/deva-cpmav9sk6x\d*\.cimanowtv\.com/, "drone.worldcdn.online");
//$(this).attr("href", link);
//}
}
});
});
$(document).ready(function(){
	
  
$('#watch > li[data-index="00"]').remove();
	
if (isSmartTV()) {
$("#xqeqjp").click(function(){
$("#xqeqjp").hide();
setTimeout(function(){
$('#xqeqjp').show();
}, 15 * 60000); 
});
$("#xqeqjp3").click(function(){
$("#xqeqjp3").hide();
setTimeout(function(){
$('#xqeqjp3').show();
}, 15 * 60000); 
});    }

function isSmartTV() {
    var userAgent = navigator.userAgent.toLowerCase();
    return /smart-tv|smarttv|hbbtv|netcast|webos|tizen|viera|aquos|android tv|apple tv|roku|fire tv/.test(userAgent);
} 
$('a').each(function(){
this.href = this.href.replace('web2.cnvids.com', 'fr.cimanow.cc');
});
	
$('li[aria-label="quality"]:not(.box)').remove();


});
function onFullscreenChange() { var isInFullScreen = document.fullscreenElement || document.mozFullScreenElement || document.webkitFullscreenElement || document.msFullscreenElement; if (isInFullScreen) { screen.orientation.lock("landscape-primary"); } } document.addEventListener("fullscreenchange", onFullscreenChange); document.addEventListener("webkitfullscreenchange", onFullscreenChange); document.addEventListener("mozfullscreenchange", onFullscreenChange); document.addEventListener("MSFullscreenChange", onFullscreenChange);