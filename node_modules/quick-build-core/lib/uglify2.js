module.exports = (function(){
	'use strict';
	var UglifyJS = require('uglify-js');
	var _ = {};
	//代码压缩
	_.min = function(content,uglifyOps){
		return UglifyJS.minify(content, {
			fromString: true,
			output:uglifyOps.output,
			compress:uglifyOps.options
		});
	}
	return _;
})();