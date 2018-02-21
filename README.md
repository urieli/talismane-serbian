# Serbian extensions for Talismane

To use these extensions, go the parent directory where you want to place this, and:
```
git clone https://github.com/urieli/talismane-serbian.git
cd talismane-serbian
mvn clean dependency:copy-dependencies package
```

Now, copy the contents of directory `talismane-serbian/target/talismane_sr-0.0.1-SNAPSHOT-bin` to a directory where you wish to run your commands.

In this new directory, you can use the following commands.

Assume your test corpus is `corpus/test.conll`.

Projectify the corpus:
```
java -jar -Dconfig.file=conf/talismane-sr-projectify.conf talismane_sr-0.0.1-SNAPSHOT.jar --sessionId=sr --module=parser --process --inFile=corpus/test.conll --outFile=corpus-proj/test-proj.conll
```

Analyse the test corpus using Talismane (configuration file not included in this project, as it depends on your resources):
```
java -jar -Dconfig.file=conf/talismane-sr-analyse.conf talismane_sr-0.0.1-SNAPSHOT.jar --sessionId=sr --module=parser --analyse --inFile=corpus-proj/test-proj.conll --outFile=corpus-eval/test-proj-eval.conll
```

De-projectify the analysed file:
```
java -jar -Dconfig.file=conf/talismane-sr-deprojectify.conf talismane_sr-0.0.1-SNAPSHOT.jar --sessionId=sr --module=parser --process --inFile=corpus-eval/test-proj-eval.conll --outFile=corpus-eval/test-deproj-eval.conll
```

Evaluate the de-projectivised links:
```
java -jar -Dconfig.file=conf/talismane-sr-nonproj-eval.conf talismane_sr-0.0.1-SNAPSHOT.jar --sessionId=sr --module=parser --compare --inFile=corpus-proj/test-proj.conll --evalFile=corpus-eval/test-deproj-eval.conll --outDir=corpus-eval-results/
```

