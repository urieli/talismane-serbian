package com.joliciel.talismane.sr;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.joliciel.talismane.TalismaneException;
import com.joliciel.talismane.TalismaneSession;
import com.joliciel.talismane.parser.DependencyArc;
import com.joliciel.talismane.parser.ParseConfiguration;
import com.joliciel.talismane.parser.evaluate.ParseEvaluationObserver;
import com.joliciel.talismane.posTagger.PosTag;
import com.joliciel.talismane.posTagger.PosTagSequence;
import com.joliciel.talismane.posTagger.PosTaggedToken;
import com.joliciel.talismane.stats.FScoreCalculator;

/**
 * Evaluate non-projective labels/heads for Serbian.<br/>
 * <br/>
 * In order to concentrate on cases we handle, it splits the evaluation by head
 * tag/dep tag, as well as indicating whether adjective with a non-projective
 * label precedes its direct or indirect projectified verbal governor, and if it
 * even has a verbal governor.
 * 
 * @author Assaf Urieli
 *
 */
public class NonProjectiveEvaluator implements ParseEvaluationObserver {
  private static final Logger LOG = LoggerFactory.getLogger(NonProjectiveEvaluator.class);
  private final Writer writer;
  private final FScoreCalculator<String> fscoreCalculator = new FScoreCalculator<String>();
  private Map<String, String> tagMap = new HashMap<>();

  public NonProjectiveEvaluator(File outDir, TalismaneSession session) throws FileNotFoundException {
    File fscoreFile = new File(outDir, session.getBaseName() + ".nproj-fscores.csv");
    this.writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fscoreFile, false), session.getCsvCharset()));

    tagMap.put("A", "Adj");
    tagMap.put("Abr", "Adv");
    tagMap.put("Adv", "Adv");
    tagMap.put("C_coord", "C");
    tagMap.put("C_sub", "C");
    tagMap.put("I", "Adv");
    tagMap.put("L", "X");
    tagMap.put("N", "N");
    tagMap.put("Num", "Adj");
    tagMap.put("P", "N");
    tagMap.put("Part", "Adv");
    tagMap.put("Prep", "Adv");
    tagMap.put("V_main", "V");
    tagMap.put("V_aux", "Adv");
    tagMap.put("X", "X");
    tagMap.put("Z", "X");
  }

  @Override
  public void onParseStart(ParseConfiguration realConfiguration, List<PosTagSequence> posTagSequences) {
  }

  @Override
  public void onParseEnd(ParseConfiguration realConfiguration, List<ParseConfiguration> guessedConfigurations) throws TalismaneException, IOException {
    PosTagSequence posTagSequence = realConfiguration.getPosTagSequence();
    ParseConfiguration bestGuess = guessedConfigurations.get(0);
    int mismatchedTokens = 0;
    for (PosTaggedToken posTaggedToken : posTagSequence) {
      if (!posTaggedToken.getTag().equals(PosTag.ROOT_POS_TAG)) {
        DependencyArc realArc = realConfiguration.getGoverningDependency(posTaggedToken, false);
        DependencyArc realProjArc = realConfiguration.getGoverningDependency(posTaggedToken, true);
        DependencyArc guessedArc = null;
        DependencyArc guessedProjArc = null;

        boolean foundToken = false;
        for (PosTaggedToken guessedToken : bestGuess.getPosTagSequence()) {
          if (guessedToken.getToken().getStartIndex() == posTaggedToken.getToken().getStartIndex()) {
            if (guessedToken.getToken().isEmpty() && !posTaggedToken.getToken().isEmpty())
              continue;
            if (!guessedToken.getToken().isEmpty() && posTaggedToken.getToken().isEmpty())
              continue;
            foundToken = true;
            guessedArc = bestGuess.getGoverningDependency(guessedToken, false);
            guessedProjArc = bestGuess.getGoverningDependency(guessedToken, true);
            break;
          }
        }

        if (!foundToken) {
          LOG.info("Mismatched token :" + posTaggedToken.getToken().getOriginalText() + ", index " + posTaggedToken.getToken().getIndex());
          mismatchedTokens += 1;
        }

        String realLabel = realArc == null ? "noHead" : realArc.getLabel();
        String guessedLabel = guessedArc == null ? "noHead" : guessedArc.getLabel();

        if (realLabel.endsWith("-nproj") || guessedLabel.endsWith("-nproj")) {
          if (realLabel == null || realLabel.length() == 0)
            realLabel = "noLabel";
          if (guessedLabel == null || guessedLabel.length() == 0)
            guessedLabel = "noLabel";

          if (realLabel.equals("Dep-nproj")) {
            PosTag headTag = realArc.getHead().getTag();
            PosTag depTag = realArc.getDependent().getTag();
            realLabel = "Dep" + tagMap.get(headTag.getCode()) + tagMap.get(depTag.getCode()) + "-nproj";
          }

          if (guessedLabel.equals("Dep-nproj")) {
            PosTag headTag = guessedArc.getHead().getTag();
            PosTag depTag = guessedArc.getDependent().getTag();
            guessedLabel = "Dep" + tagMap.get(headTag.getCode()) + tagMap.get(depTag.getCode()) + "-nproj";
          }

          if (realLabel.equals("DepNAdj-nproj") && realProjArc != null) {
            PosTaggedToken verb = realProjArc.getHead();
            while (!"V_main".equals(verb.getTag().getCode())) {
              verb = realConfiguration.getHead(verb, true);
              if (verb == null)
                break;
            }

            if (verb == null) {
              realLabel = "DepNAdj-noverb-nproj";
            } else if (verb.getIndex() < realProjArc.getDependent().getIndex()) {
              realLabel = "DepNAdj-post-nproj";
            }
          }

          if (guessedLabel.equals("DepNAdj-nproj") && guessedProjArc != null) {
            PosTaggedToken verb = guessedProjArc.getHead();
            while (!"V_main".equals(verb.getTag().getCode())) {
              verb = bestGuess.getHead(verb, true);
              if (verb == null)
                break;
            }

            if (verb == null) {
              guessedLabel = "DepNAdj-noverb-nproj";
            } else if (verb.getIndex() < guessedProjArc.getDependent().getIndex()) {
              guessedLabel = "DepNAdj-post-nproj";
            }
          }

          // anything attached "by default" to the root, without a label,
          // should be considered a "no head" rather than "no label"
          if (realArc != null && realArc.getHead().getTag().equals(PosTag.ROOT_POS_TAG) && realLabel.equals("noLabel"))
            realLabel = "noHead";
          if (guessedArc != null && guessedArc.getHead().getTag().equals(PosTag.ROOT_POS_TAG) && guessedLabel.equals("noLabel"))
            guessedLabel = "noHead";

          LOG.info("Real " + realLabel + ". Non-proj: " + realArc + ". Proj: " + realProjArc);
          LOG.info("Gues " + guessedLabel + ". Non-proj: " + guessedArc + ". Proj: " + guessedProjArc);

          if (realArc == null || guessedArc == null) {
            fscoreCalculator.increment(realLabel, guessedLabel);
          } else {
            boolean sameHead = realArc.getHead().getToken().getStartIndex() == guessedArc.getHead().getToken().getStartIndex();

            if (sameHead) {
              fscoreCalculator.increment(realLabel, guessedLabel);
            } else if (guessedLabel.equals("noHead")) {
              fscoreCalculator.increment(realLabel, "noHead");
            } else if (realArc.getLabel().equals(guessedArc.getLabel())) {
              fscoreCalculator.increment(realLabel, "wrongHead");
            } else {
              fscoreCalculator.increment(realLabel, "wrongHeadWrongLabel");
            }

          } // have one of the arcs
        } // is root tag?
      } // next pos-tagged token

      if ((double) mismatchedTokens / (double) posTagSequence.size() > 0.5) {
        // more than half of the tokens mismatched?
        throw new TalismaneException("Too many mismatched tokens in sentence: " + posTagSequence.getTokenSequence().getSentence().getText());
      }
    }
  }

  @Override
  public void onEvaluationComplete() throws IOException {
    double fscore = fscoreCalculator.getTotalFScore();
    LOG.debug("F-score: " + fscore);
    fscoreCalculator.writeScoresToCSV(writer);
    writer.flush();
    writer.close();
  }

}
