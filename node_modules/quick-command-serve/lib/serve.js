/**
 * Created by johnkim on 15-1-20.
 */
var path = require('path');
var fs = require('fs');
var http = require('http');
var url = require('url');
//默认配置
var confs = require('./default_conf');

module.exports = initServer = function(quick) {

    //合并配置
    confs = quick.util.merge(quick.config.serve || {}, confs);
    var $mine = confs.mine;
    var $rules = confs.rules;
    var $transfer = confs.transfer;
    var $combo = confs.combo;
    var $basePath = (quick.config.serve.root_temp != '' ?
        quick.config.serve.root_temp :
        (quick.util._.has(confs, 'root') ?
            confs.root :
            process.cwd()));

    var _ = {};

    quick.log.ok('用户设定监听路径为：\n' + $basePath);

    _.server = http.createServer(function(request, response) {
        
        var pathname = url.parse(request.url);
        //请求首页
        if (pathname.pathname == '/home') {
            response.writeHead(200, {
                'Content-Type': 'text/html;charset=utf-8'
            });
            response.write("<xmp theme='united' style='display:none;'>" + fs.readFileSync(path.join(__dirname, '..', '/README.md'), {
                encoding: 'utf8'
            }) + "</xmp><script src='http://strapdownjs.com/v/0.2/strapdown.js'></script>", 'utf8');
            response.end();
            return;
        } else {
            pathname = url.parse(request.url).path;
        }
        //检测combo
        var splitIndex = pathname.indexOf('??');
        //是combo请求
        if (splitIndex != -1) {
            var commonPath = pathname.slice(0, splitIndex);
            var last = commonPath.slice(commonPath.length - 1);
            if (last != '/' && last != '\\') {
                //如果没在??后面写上/，则自动加上/，否则会出现路径问题
                commonPath+='/';
            }
            //分离出请求文件列表
            var names = pathname.slice(splitIndex + 2, pathname.length).split(',');
            //用来缓存各文件内容
            var sb = [];
            //取文件
            names.forEach(function(name, idx) {
                var realPath = path.join($basePath, commonPath, name);
                fs.exists(realPath, function(exists) {
                    if (!exists) {
                        response.writeHead(404, {
                            'Content-Type': 'text/plain'
                        });
                        quick.log.error('404 failure to request file:\t' + request.url);
                        response.write("This request URL " + name + " was not found on this server.");
                        response.end();
                        return;
                    } else {
                        fs.readFile(realPath, "binary", function(err, file) {
                            if (err) {
                                response.writeHead(500, {
                                    'Content-Type': 'text/plain'
                                });
                                quick.log.error('500 can not to read file:\t' + request.url);
                                response.end(err);
                                return;
                            } else {
                                var ext = path.extname(realPath);
                                ext = ext ? ext.slice(1) : 'unknown';
                                var contentType = $mine[ext] || "text/plain";
                                response.writeHead(200, {
                                    'Content-Type': contentType,
                                    'Access-Control-Allow-Origin': '*'
                                });
                                if ($rules(pathname, ext)) {
                                    for (extname in $transfer) {
                                        if (extname == ext) {
                                            file = $transfer[extname](file);
                                            quick.log.ok('requested file:\t' + request.url);
                                            sb.push(file);
                                            if (idx == names.length - 1) {
                                                response.write(sb.join('\n'), "binary");
                                                response.end();
                                                return;
                                            }
                                        }
                                    }
                                } else {
                                    quick.log.ok('requested file:\t' + request.url);
                                    sb.push(file);
                                    if (idx == names.length - 1) {
                                        response.write(sb.join('\n'), "binary");
                                        response.end();
                                        return;
                                    }
                                }
                            }
                        });
                    }
                });
            });
        } else {
            //是普通请求
            var realPath = path.join($basePath, pathname);
            var ext = path.extname(realPath);
            fs.exists(realPath, function(exists) {
                if (!exists) {
                    if (ext == '.js') {
                        realPath = realPath.replace(ext, '.tpl');
                        fs.exists(realPath, function(exists) {
                            if (!exists) {
                                response.writeHead(404, {
                                    'Content-Type': 'text/plain'
                                });
                                quick.log.error('404 failure to request file:\t' + request.url);
                                response.write("This request URL " + pathname + " was not found on this server.");
                                response.end();
                            } else {
                                readFileAndRes(realPath, pathname, request, response);
                            }
                        });
                    } else {
                        response.writeHead(404, {
                            'Content-Type': 'text/plain'
                        });
                        quick.log.error('404 failure to request file:\t' + request.url);
                        response.write("This request URL " + pathname + " was not found on this server.");
                        response.end();
                    }
                } else {
                    readFileAndRes(realPath, pathname, request, response);
                }
            });
        }
    });

    function readFileAndRes(realPath, pathname, request, response) {
        fs.readFile(realPath, "binary", function(err, file) {
            if (err) {
                response.writeHead(500, {
                    'Content-Type': 'text/plain'
                });
                quick.log.error('500 can not to read file:\t' + request.url);
                response.end(err);
            } else {
                var ext = path.extname(realPath);
                ext = ext ? ext.slice(1) : 'unknown';
                var contentType = $mine[ext] || "text/plain";
                response.writeHead(200, {
                    'Content-Type': contentType,
                    'Access-Control-Allow-Origin': '*'
                });

                if ($rules(pathname, ext)) {
                    for (extname in $transfer) {
                        if (extname == ext) {
                            file = $transfer[extname](file);
                            quick.log.ok('requested file:\t' + request.url);
                            response.write(file, "binary");
                            response.end();
                            return;
                        }
                    }
                } else {
                    quick.log.ok('requested file:\t' + request.url);
                    response.write(file, "binary");
                    response.end();
                }
            }
        });
    }
    return _;
};