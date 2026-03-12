'use strict';

exports.name = 'build';
exports.usage = '<names> [options]';
exports.desc = 'build components';

var fs = require('fs');
var path = require('path');

exports.register = function(commander,quick){

    commander
        .option('-r, --root <path>', 'set build root')
        .option('-t, --temp <path>', 'set up temporary folders for build')
        .option('-i, --inf <path>', 'set build in folder')
        .option('-o, --outf <path>', 'set build out folder')
        .option('-q, --queue <string>', 'set build queue,Use comma(,) separated or all')
        .option('-m, --moreLog <boolean>', 'see more logs')
        .option('-u, --uglify <boolean>', 'uglify js')
        .option('-p, --id <string>', 'set module prefix')
        .option('-c, --clear <boolean>', 'clear temporary folders when building complete')
        .option('-d, --delay <number>', 'set delay (ms)')

        .action(function(){
            var args = [].slice.call(arguments);
            var options = args.pop();
            options.root = path.resolve(process.cwd(),options.root || '..');
            var settings = {
                root: options.root,
                temp: options.temp || '../tmp',
                inf: path.resolve(process.cwd(),options.inf || options.root),
                outf: path.resolve(process.cwd(),options.outf || '../../'),
                queue: options.queue?(options.queue=='all'?'all':(options.queue+"").split(',')):'',
                moreLog: options.moreLog || false,
                uglify: options.uglify || true,
                id: options.id || 'gallery',
                clear: options.clear,
                delay: options.delay
            };

            require('./lib/build')(settings).start();
        });
};