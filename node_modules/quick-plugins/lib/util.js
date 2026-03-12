var register = function(quick){
    var _ = {};
    _.Promise = require('bluebird');
    _.findup = require('findup');
    _.fs = require('fs');
    _._ = require('lodash');
    // Windows?
    var win32 = process.platform === 'win32';
    var confName = quick.config.confFileName;

    //You can't use merge in util.js
    _.merge = function(source,target){
        if(typeof source === 'object' && typeof target === 'object'){
            for(var key in source){
                if(target.hasOwnProperty(key)) {
                    if(typeof target[key] === 'function' ){
                        target[key] = source[key];
                    }else{
                        source[key] = _.merge(source[key], target[key]);    
                    }
                }else{
                    target[key] = source[key];
                }
            }
            for(var key in target){
                if(!source.hasOwnProperty(key)) {
                    source[key] = target[key];
                }
            }
        } else {
            source = target;
        }
        return source;
    };
    /***
     * 递归查找配置文件
     * @param done
     * @returns {*}
     */
    _.findConf = function(done){
        return new _.Promise(function(resolve, reject) {
            var fup = _.findup(process.cwd(), confName);
            var dir = null;

            fup.on('found', function(found) {
                dir = found;
                fup.stop();
            });

            fup.on('error', reject);

            fup.on('end', function() {
                //quick.log.debug('查找qconf配置文件已结束！');
                if(dir!=null){
                    resolve(dir);
                }else{
                    quick.log.error('请检查{{name}}配置文件是否存在！'.replace('{{name}}',confName));
                }
            });
        }).then(function(dir){
                done(dir);
            }).catch(function (e) {
                quick.log.error('请检查{{name}}配置文件是否存在！'.replace('{{name}}',confName));
            }).error(function(e) {
                quick.log.error('查找{{name}}配置文件失败！'.replace('{{name}}',confName));
            });
    };

    _.hasArgv = function(argv, search){
        var pos = argv.indexOf(search);
        var ret = false;
        while(pos > -1){
            argv.splice(pos, 1);
            pos = argv.indexOf(search);
            ret = true;
        }
        return ret;
    };

    /***
     * trim函数,作用为去掉字符串中所有的空字符串
     * @param str
     * @returns {XML|string|void}
     */
    _.trim = function(str){
        return str.replace(/(^\s*)|(\s*$)/g,"");
    };

    /***
     * 取package.json中的某一项值
     * @param key
     * @param file
     * @returns {*}
     */
    _.pkg = function(key, file){
        var result;
        key = key || '';
        file = file || 'package.json';
        try {
            // 注意；readFileSync的返回值是object类型，不是string类型
            result = JSON.parse(_.fs.readFileSync(file));
            return key ? result[key] : result;
        } catch(e) {
            throw Error('_.pkg(): JSON.parse error');
        }
    };
    /***
     * 获取系统用户目录
     * @returns {*}
     */
    _.userDir = function() {
        return process.env[win32 ? 'USERPROFILE' : 'HOME'];
    };
    return _;
};
module.exports = {
    register:register
};