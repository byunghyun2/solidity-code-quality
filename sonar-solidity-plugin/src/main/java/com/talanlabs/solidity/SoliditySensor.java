package com.talanlabs.solidity;

import com.talanlabs.solidity.model.ValidationError;
import com.talanlabs.solidity.model.ValidationResults;
import com.talanlabs.solidity.rules.ThrowDeprecatedRuleChecker;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultTextPointer;
import org.sonar.api.batch.fs.internal.DefaultTextRange;
import org.sonar.api.batch.fs.internal.FileExtensionPredicate;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.rule.RuleKey;

import java.io.IOException;
import java.util.List;

import static com.talanlabs.solidity.SolidityRulesDefinition.REPOSITORY_KEY;

public class SoliditySensor implements Sensor {
    public static final String NAME = "Solidity Sensor";
    private static final Logger LOG = LoggerFactory.getLogger(SoliditySensor.class);

    @Override
    public void describe(SensorDescriptor descriptor) {
        descriptor.name(NAME);
        descriptor.createIssuesForRuleRepository(REPOSITORY_KEY);
    }

    @Override
    public void execute(SensorContext context) {
        FileSystem fs = context.fileSystem();
        Iterable<InputFile> files = fs.inputFiles(new FileExtensionPredicate("sol"));
        files.forEach(file -> analyzeContract(context, file));
    }

    private void analyzeContract(SensorContext context, InputFile file) {
        String absoluteFilePath = file.absolutePath();
        try {
            LOG.info("Analyzing Solidity contract '{}'...", absoluteFilePath);
            SolidityParser.SourceUnitContext tree = parse(absoluteFilePath);
            RulesRepository rulesRepository = new RulesRepository();
            for (RuleChecker rule : rulesRepository.getRules()) {
                RuleChecker visitor = new ThrowDeprecatedRuleChecker();
                ValidationResults results = rule.visit(tree);
                List<ValidationError> errors = results.getErrors();
                LOG.info("Found {} error(s)", errors.size());

                // loop through all detected errors and generate appropriate Sonar errors
                for (ValidationError error : errors) {
                    NewIssue issue = context.newIssue()
                            .forRule(RuleKey.of(REPOSITORY_KEY, error.getCode()));
                    LOG.info("Found {} issue: {} in file {}", error.getCriticity(), error.getCode(), absoluteFilePath);
                    issue.at(
                            issue.newLocation()
                                    .on(file)
                                    .message(error.getMessage())
                                    .at(new DefaultTextRange(
                                            new DefaultTextPointer(error.getStart().getLine(), error.getStart().getColumn()),
                                            new DefaultTextPointer(error.getStop().getLine(), error.getStop().getColumn()))
                                    )
                    );
                    issue.save();
                }
            }
        } catch (IOException e) {
            LOG.error("Can't analyze contract '" + absoluteFilePath + "'", e);
        }
    }

    private Severity convertSeverity(ValidationError error) {
        switch (error.getCriticity()) {
            case BLOCKER:
                return Severity.BLOCKER;
            case CRITICAL:
                return Severity.CRITICAL;
            case MAJOR:
                return Severity.MAJOR;
            case MINOR:
                return Severity.MINOR;
            default:
                throw new UnsupportedOperationException("Can't convert " + error.getCriticity() + " to Sonar criticity");
        }
    }

    private static SolidityParser.SourceUnitContext parse(String fileName) throws IOException {
        CharStream input = CharStreams.fromFileName(fileName);
        SolidityLexer lexer = new SolidityLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SolidityParser parser = new SolidityParser(tokens);
        parser.setBuildParseTree(true);
        return parser.sourceUnit();
    }

}
