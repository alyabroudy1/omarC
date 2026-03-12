var rDefine = /define\(\s*(['"](.+?)['"],)?/;
module.exports = {
	mine: {
		//mine类型
		"css": "text/css",
		"gif": "image/gif",
		"html": "text/html",
		"tpl": "text/javascript",
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
	},
    rules: function(pathname, ext) { //执行wrap条件，用户可覆盖此默认配置
        return pathname.indexOf('static/page') != -1 && ext == 'js';
    },
    transfer: { //转换器，用户可覆盖或修改默认配置
        js: function(content) {
            if (rDefine.test(content)) { //如果已经存在包装了，就直接返回原￥数据
                return content;
            }
            return [
                'define(function(require, exports, module) {\n',
                content,
                '\n});'
            ].join('');
        }
    }
}