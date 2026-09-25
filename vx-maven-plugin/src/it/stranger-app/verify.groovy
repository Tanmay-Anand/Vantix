// Runs after the three invocations in invoker.properties; `basedir` is the cloned project.
def log = new File(basedir, 'build.log').text
def model = 'com/example/stranger/model'

assert new File(basedir, 'vantix/schema.vx').text.contains('package = "com.example.stranger.model"')
assert new File(basedir, '.gitignore').text.contains('target/generated-sources/vantix/')
assert new File(basedir, "target/generated-sources/vantix/$model/User.java").isFile()
assert new File(basedir, "target/classes/$model/UserRepository.class").isFile()
assert new File(basedir, 'target/classes/com/example/stranger/Welcome.class').isFile()

// First compile: everything is new.
assert log.contains('(2 written, 0 unchanged)') : 'first build should write the entity and repository'
// Second compile, no changes: Vantix rewrites nothing, so javac has nothing to do.
assert log.contains('(0 written, 2 unchanged)') : 'second build must not rewrite generated sources'
def compiles = (log =~ /Compiling \d+ source files?/).count
def upToDate = log.count('Nothing to compile - all classes are up to date')
assert compiles == 1 : "javac should run once, ran $compiles times"
assert upToDate == 1 : 'second build should find all classes up to date'
return true
