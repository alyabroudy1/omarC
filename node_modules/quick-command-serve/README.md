# quick-command-serve

    启动一个本地静态资源服务器，支持combo

## Install
    请直接安装quickjs
    $ npm install quickjs -g
    如果你安装了taobao的代理镜像
    $ cnpm install quickjs -g 

## Usage

    Usage: quick serve [options]

    Options:
    
      -r, --root <path>       set listen root
      -p, --port <port>        set listen port

## Default qconf
```
serve:{
    //监听路径
    root:"//默认为当前命令行路径",
    //监听端口
    port:2000,
    //执行wrap条件，用户可覆盖此默认配置
    rules : function(pathname,ext){
        return pathname.indexOf('static/page') != -1 && ext == 'js';
    },
    //wrap转换器，用户可覆盖或修改默认配置
    transfer: { 
        js: function(content) {
            return [
                'define(function(require, exports, module) {\n',
                content,
                '\n});'
            ].join('');
        },
        tpl:function(content){
            return this.js("return '"+content.replace(/\s/g,' ')+"'");
        }
    },
    mine: { //mine类型
        "css": "text/css",
        "gif": "image/gif",
        "html": "text/html",
        "ico": "image/x-icon",
        "jpeg": "image/jpeg",
        "jpg": "image/jpeg",
        "js": "text/javascript",
        "json": "application/json",
        "pdf": "application/pdf",
        "png": "image/png",
        "svg": "image/svg+xml",
        "swf": "application/x-shockwave-flash",
        "tiff": "image/tiff",
        "txt": "text/plain",
        "wav": "audio/x-wav",
        "wma": "audio/x-ms-wma",
        "wmv": "video/x-ms-wmv",
        "xml": "text/xml"
    }
}
```

## Diy
    可自定义任何选项，任何选项都是可选的

## More
    Please See source code
> lib/default_conf.js

## Github


_https://github.com/dreamstu/quick-command-serve.git_