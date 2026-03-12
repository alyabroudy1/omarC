'use strict';

exports.name = 'init';
exports.usage = '<names> [options]';
exports.desc = 'Initialize a module';

var enter = function(program,quick){
    program
        .option('-r, --root <path>', 'set serve root')
        .option('-t, --template [value]', 'set template name')
        .option('-u, --upgrade [b]', 'update available template.')
        .parse(process.argv);

    var settings = {
        root: program.root || '.',
        template:program.template || 'seajs',
        upgrade:program.upgrade || false
    };
    var init = require('./lib/init')(quick,settings);
    init.start();
}
exports.register = enter;
