var color = require('colorful').color;
var path = require('path');
var fs = require('fs');
var exists = fs.existsSync;
var helper = require('./helper');
var program = require('commander');
var inquirer = require('inquirer');
var semver = require('semver');
var vfs = require('vinyl-fs');
var emptyDir = require('empty-dir').sync;
var gulpTemplate = require('gulp-template');
var NAME_REGEX = /^[a-z][a-z0-9\-\.]*$/;

module.exports = main = function(quick, opts) {

    var exports = {};

    var template = opts.template;

    var templatepath = path.join(helper.initDir, template);

    var giturl = 'git://github.com/dreamstu/quick-init-{{template}}.git'.replace('{{template}}', template);

    var upgrade = opts.upgrade;

    /***
     * 必须的grunt-init是否安装好，以及quick template是否安装好
     * @returns {*}
     */
    exports.start = function() {
        if (!exists(templatepath)) {
            helper.gitInstall(giturl, templatepath)
                .on('close', runTask);
        } else {
            if (upgrade) {
                helper.gitInstall(giturl, templatepath)
                 .on('close', runTask);
            }else{
                runTask();
            }
        }
    };

    /*
     run grunt init task
     */
    function runTask() {
        program
            .option('-p, --package', 'Just add a package.json into your project.')
            .on('--help', function() {
                console.log('  ' + 'Examples:');
                console.log('  $', 'quick init           Initialize a package');
                console.log('  $', 'quick init -p        Add a package.json into your project');
                console.log('');
            })
            .parse(process.argv);

        if (!emptyDir(process.cwd()) && !program.package) {
            console.warn(color.yellow('Existing files here, please run init command in an empty folder!'));
            return;
        }

        console.log('Creating a package: ');

        inquirer.prompt([{
            message: 'Package name',
            name: 'name',
            default: path.basename(process.cwd()),
            validate: function(input) {
                var done = this.async();
                if (!NAME_REGEX.test(input)) {
                    console.warn(color.red('Must be only lowercase letters, numbers, dashes or dots, and start with lowercase letter.'));
                    return;
                }
                done(true);
            }
        }, {
            message: 'Version',
            name: 'version',
            default: '0.0.1',
            validate: function(input) {
                var done = this.async();
                if (!semver.valid(input)) {
                    console.warn(color.red('Must be a valid semantic version.'));
                    return;
                }
                done(true);
            }
        }, {
            message: 'Description',
            name: 'description'
        }, {
            message: 'Author',
            name: 'author',
            default: require('whoami')
        }], function(answers) {
            answers.varName = answers.name.replace(/\-(\w)/g, function(all, letter) {
                return letter.toUpperCase();
            });
            //answers.yuanUrl = 'http://spmjs.io';

            if (program.package) {
                vfs.src(path.join(templatepath, 'package.json'))
                    .pipe(gulpTemplate(answers))
                    .pipe(vfs.dest('./'))
                    .on('end', function() {
                        console.log(color.green('Initialize a package.json Succeccfully!'));
                    });
                return;
            }
            vfs.src(['!.git/**','**'], {
                    cwd:templatepath,
                    dot: true
                })
                .pipe(gulpTemplate(answers))
                .pipe(vfs.dest('./'))
                .on('end', function() {
                    /*fs.renameSync('./.gitignore', './.gitignore');
                    fs.renameSync('./.travis.yml', './.travis.yml');*/
                    console.log(color.green('\nInitialize a package Succeccfully!\n'));
                });
        });
    };
    return exports;
};
