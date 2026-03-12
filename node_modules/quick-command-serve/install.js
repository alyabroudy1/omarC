'use strict';

exports.name = 'serve';
exports.usage = '<names> [options]';
exports.desc = 'Start a small static resource server';

var path = require('path');
var child_process = require("child_process");

exports.register = function(commander,quick){

    commander
        .option('-r, --root <path>', 'set serve root')
        .option('-p, --port <n>', 'Set the listening port')
        .action(function(){

            var Promise = quick.util.Promise;

            var args = [].slice.call(arguments);
            var options = args.pop();

            var settings = {
                root: options.root || '',
                port: options.port || ''
            };

            Promise.try(function() {
                return quick.util.findConf(function(dir){
                    var filepath =  path.resolve(dir, quick.config.confFileName);
                    require(filepath)(quick);
                    quick.config.serve.root_temp = settings.root;
                    if(settings.port==''){
                        if(quick.util._.has(quick.config.serve,'port')){
                            settings.port = quick.config.serve.port;
                        }else{
                            settings.port = 3000;
                        }
                    }
                });
            }).then(function(){
                var serve = require('./lib/serve')(quick);
                var server = serve.server;
                server.listen(settings.port,function(err){
                    if(!err){
                        quick.log.success('file server started，listening on port '+settings.port);
                        var url = "http://127.0.0.1:"+settings.port+"/home";
                        var cmd = 'start';
                        if(process.platform == 'win32'){
                            cmd  = 'start "%ProgramFiles%\Internet Explorer\iexplore.exe"';
                        }else if(process.platform == 'linux'){
                            cmd  = 'xdg-open';
                        }else if(process.platform == 'darwin'){
                            cmd  = 'open';
                        }
                        child_process.exec(cmd + ' "'+url + '"');
                    }else{
                        quick.log.error('file service failed to start!');
                    }
                });
                server.on('error',function(e) {
                    if (e.code == 'EADDRINUSE') {
                        quick.log.error('Address in use, Please change the port，file service failed to start! ');
                    } else {
                        quick.log.error('file service failed to start! Error Code：'+ e.code);
                    }
                });

            }).catch(function(e) {
                quick.log.error('\x1b[31m%s\x1b[0m', e.message);
            });
        });
};