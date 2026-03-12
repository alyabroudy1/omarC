var path = require('path');

var register = function(quick){
    var _ = {};

    /***
     * 获取运行目录下的某个文件路径
     * @param target 某个文件路径
     * @returns {string}
     */
    _.getBasePath = function(target){
        return path.join(path.resolve(process.execPath,'..','..','lib','node_modules','quickjs'),target);
    };
    _.confFileName = 'qconf.js';
    //构建日志文件所在目录名
    _.logFileFolder = 'logs';
    _.build = {};
    //利用正则过滤掉不需要构建的组件
    _.build.filter = /^(node_modules|jquery|seajs|logs)$/i;
    //默认构建路径为当前目录下的build文件夹
    _.build.path = 'build';
    //Users can override this configuration Configuration
    _.build.confs = [_.getBasePath('node_modules'),_.getBasePath('Gruntfile.js')];
    return _;
};

module.exports = {
    register:register
};