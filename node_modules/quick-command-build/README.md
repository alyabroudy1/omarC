# quick-command-build

## Usage

    Usage: build <name> [options]

    Options:
    
      -r, --root <path>       set build root
      -t, --temp <path>       set up temporary folders for build
      -i, --inf <path>       set build in folder
      -o, --outf <path>       set build out folder
      -q, --queue <string>       set build queue,Use comma(,) separated or all
      -m, --moreLog <boolean>       see more logs
      -u, --uglify <boolean>       uglify js
      -p, --id <string>       set module prefix


## 各参数含义

	root:构建基础目录
	temp:构建临时目录，相对于构建目录
	inf:需要构建的组件父目录
	outf:构建输出目录
	queue:需要构建的组件队列，例如：underscore,jquery。如需构建inf下的所有组件，则只需指定为：all 即可
	moreLog:构建时输出详细日志
	uglify:是否压缩js
	id:模块的前缀（目录）
