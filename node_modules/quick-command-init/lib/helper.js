var fs = require('fs');
var path = require('path');
var spawn = require('win-spawn');
/*
 Get init directory
 */
var homeDir = process.env.HOME || process.env.USERPROFILE;
if (!homeDir) {
    homeDir = process.env.HOMEDRIVE + process.env.HOMEPATH;
}

var defaultDir = path.join(homeDir, '.quick', 'init');
var initDir = exports.initDir = (defaultDir).replace(/^~/, homeDir);
exports.gitInstall = function (url, dest) {
    mkInitDir();
    dest = dest.replace('~', homeDir);
    if (!fs.existsSync(dest)) {
        return spawn('git', ['clone', url, dest], {stdio: 'inherit'});
    } else {
        return spawn('git', ['pull', 'origin', 'master'], {stdio: 'inherit', 'cwd': dest});
    }
};
/*
 Mkdirp
 */
function mkInitDir() {
    if (fs.existsSync(initDir)) return;
    var arr = initDir.split(path.sep);
    for (var i = 2, l = arr.length; i <= l; i++) {
        var p = arr.slice(0, i).join(path.sep);
        if (fs.existsSync(p)) continue;
        fs.mkdirSync(p);
    }
};
