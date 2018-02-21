package com.joliciel.talismane.sr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.joliciel.talismane.TalismaneException;
import com.joliciel.talismane.TalismaneSession;
import com.joliciel.talismane.lexicon.LexicalEntry;
import com.joliciel.talismane.parser.DependencyArc;
import com.joliciel.talismane.parser.ParseConfiguration;
import com.joliciel.talismane.parser.output.ParseConfigurationProcessor;
import com.joliciel.talismane.posTagger.PosTagSequence;
import com.joliciel.talismane.posTagger.PosTaggedToken;
import com.typesafe.config.Config;

/**
 * A class for recovering the non-projective head in Serbian analysis. Currently
 * limited to recovering the non-projective nominal head of adjectives, in the
 * case where the adjectives precede their direct or indirect projectified
 * verbal governor.
 * 
 * @author Assaf Urieli
 *
 */
public class Deprojectifier implements ParseConfigurationProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(Deprojectifier.class);

  private static final Set<String> depsNotForNouns = new HashSet<>(Arrays.asList("ComplPrep", "DepEx_Suj"));
  private static final Set<String> depsForAdjectives = new HashSet<>(Arrays.asList("Suj", "ObjDir", "Suj-nproj", "ObjDir-nproj"));

  private final String nounAdjDep;
  private Map<String, Integer> invalid = new TreeMap<>();

  public Deprojectifier(TalismaneSession session) {
    Config config = session.getConfig();
    nounAdjDep = config.getString("talismane.serbian.deprojectifier.nounAdjDep");
  }

  @Override
  public void onNextParseConfiguration(ParseConfiguration parseConfiguration) throws TalismaneException, IOException {
    for (DependencyArc arc : parseConfiguration.getDependencies()) {
      parseConfiguration.addManualNonProjectiveDependency(arc.getHead(), arc.getDependent(), arc.getLabel());
    }

    for (DependencyArc arc : parseConfiguration.getDependencies()) {
      if (nounAdjDep.equals(arc.getLabel())) {
        LOG.info(arc.toString());

        PosTaggedToken nonProjHead = arc.getHead();

        boolean dealWithThis = true;

        PosTaggedToken adj = arc.getDependent();
        if (!"A".equals(adj.getTag().getCode()) && !"Num".equals(adj.getTag().getCode())) {
          LOG.debug(arc.getLabel() + " dep is " + adj.getTag() + ", expected A or Num");
          dealWithThis = false;
          incrementInvalid("Dep " + adj.getTag().getCode());
        }

        PosTaggedToken verb = arc.getHead();
        while (!"V_main".equals(verb.getTag().getCode())) {
          verb = parseConfiguration.getHead(verb);
          if (verb == null)
            break;
        }
        if (verb == null) {
          LOG.debug(arc.getLabel() + " verbal head not found");
          dealWithThis = false;
          incrementInvalid("No verb gov");
        }

        if (dealWithThis && adj.getIndex() > verb.getIndex()) {
          LOG.info(arc.getLabel() + " dep to the right of head, expected left.");
          dealWithThis = false;
          incrementInvalid("Dep after verb");
        }

        List<PosTaggedToken> candidates = new ArrayList<>();

        if (dealWithThis) {
          PosTagSequence sequence = parseConfiguration.getPosTagSequence();
          // find the nearest noun or subject pronoun between the verb and the
          // adjective
          LOG.debug("Finding noun between adjective and verb");
          boolean haveVerbalDeps = false;
          for (int i = adj.getIndex() + 1; i < verb.getIndex(); i++) {
            PosTaggedToken token = sequence.get(i);
            LOG.debug("Testing " + token);
            if (haveVerbalDeps) {
              if ("N".equals(token.getTag().getCode())) {
                DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
                if (!depsNotForNouns.contains(depArc.getLabel())) {
                  if (morphCompatible(token, adj)) {
                    candidates.add(token);
                    break;
                  }
                }
              } else if ("P".equals(token.getTag().getCode())) {
                // subject pronouns allowed as well
                DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
                if (depsForAdjectives.contains(depArc.getLabel())) {
                  if (morphCompatible(token, adj)) {
                    candidates.add(token);
                    break;
                  }
                }
              }
            }
            if (parseConfiguration.getHead(token).equals(verb)) {
              LOG.debug("haveVerbalDeps");
              haveVerbalDeps = true;
            }
          } // tokens between adjective and verb

          // find the nearest noun or subject pronoun prior to the adjective
          // and separated by verbal dependencies
          haveVerbalDeps = false;
          LOG.debug("Finding noun before adjective");
          for (int i = adj.getIndex() - 1; i > 0; i--) {
            PosTaggedToken token = sequence.get(i);
            LOG.debug("Testing " + token);
            if (haveVerbalDeps) {
              if ("N".equals(token.getTag().getCode())) {
                DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
                if (!depsNotForNouns.contains(depArc.getLabel())) {
                  if (morphCompatible(token, adj)) {
                    if (morphCompatible(token, adj)) {
                      candidates.add(token);
                      break;
                    }
                  }
                }
              } else if ("P".equals(token.getTag().getCode())) {
                // subject pronouns allowed as well
                DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
                if (depsForAdjectives.contains(depArc.getLabel())) {
                  if (morphCompatible(token, adj)) {
                    candidates.add(token);
                    break;
                  }
                }
              }
            }
            if (parseConfiguration.getHead(token).equals(verb)) {
              LOG.debug("haveVerbalDeps");
              haveVerbalDeps = true;
            }
          } // tokens between adjective and verb

          // first noun after the verb
          LOG.debug("Finding noun after verb");
          for (int i = verb.getIndex() + 1; i < sequence.size(); i++) {
            PosTaggedToken token = sequence.get(i);
            LOG.debug("Testing " + token);
            if ("N".equals(token.getTag().getCode())) {
              DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
              if (!depsNotForNouns.contains(depArc.getLabel())) {
                if (morphCompatible(token, adj)) {
                  candidates.add(token);
                  break;
                }
              }
            } else if ("P".equals(token.getTag().getCode())) {
              // subject pronouns allowed as well
              DependencyArc depArc = parseConfiguration.getGoverningDependency(token);
              if (depsForAdjectives.contains(depArc.getLabel())) {
                if (morphCompatible(token, adj)) {
                  candidates.add(token);
                  break;
                }
              }
            }
          }

          if (candidates.size() > 0) {
            incrementInvalid("valid");

            PosTaggedToken bestCandidate = null;
            int minDistance = Integer.MAX_VALUE;
            for (PosTaggedToken candidate : candidates) {
              int distance = Math.abs(candidate.getIndex() - adj.getIndex());
              if (distance < minDistance) {
                minDistance = distance;
                bestCandidate = candidate;
              }
            }
            nonProjHead = bestCandidate;
          } else {
            incrementInvalid("no candidates");
          }
        }

        DependencyArc nonProjArc = null;
        for (DependencyArc arc1 : parseConfiguration.getNonProjectiveDependencies()) {
          if (arc1.getDependent().equals(adj)) {
            nonProjArc = arc1;
            break;
          }
        }
        if (nonProjArc == null) {
          LOG.info("Couldn't find non-proj arc!!!");
        }

        // if we didn't find a non-projective head, we systematically replace
        // the non-projective head with the projective one, to avoid
        // skewing results for the training corpus
        if (nonProjArc != null)
          parseConfiguration.removeNonProjectiveDependency(nonProjArc);
        parseConfiguration.addManualNonProjectiveDependency(nonProjHead, adj, arc.getLabel());
      } // is Dep-nproj or equivalent
    } // next dependency
  }

  public boolean morphCompatible(PosTaggedToken head, PosTaggedToken dep) {
    List<LexicalEntry> headEntries = head.getLexicalEntries();
    List<LexicalEntry> depEntries = dep.getLexicalEntries();

    if (headEntries.size() == 0 || depEntries.size() == 0) {
      LOG.debug("Entries for " + head + ": " + headEntries.size());
      LOG.debug("Entries for " + dep + ": " + depEntries.size());
      LOG.debug("Automatic match");
      return true;
    }

    for (LexicalEntry headEntry : headEntries) {
      LOG.debug("head entry for " + head + ": " + headEntry);
      Set<String> headGenders = headEntry.getGender().stream().filter(g -> !("-".equals(g))).collect(Collectors.toSet());
      for (LexicalEntry depEntry : depEntries) {
        LOG.debug("dep entry for " + dep + ": " + depEntry);
        Set<String> commonGenders = new HashSet<>(headGenders);
        commonGenders.retainAll(depEntry.getGender());
        if (headGenders.size() > 0 && depEntry.getGender().size() > 0 && commonGenders.size() == 0) {
          LOG.debug("No match on " + head + " and " + dep + ". Gender: " + headEntry.getGender() + " vs " + depEntry.getGender());
          continue;
        }

        Set<String> commonNumbers = new HashSet<>(headEntry.getNumber());
        commonNumbers.retainAll(depEntry.getNumber());
        if (headEntry.getNumber().size() > 0 && depEntry.getNumber().size() > 0 && commonNumbers.size() == 0) {
          LOG.debug("No match on " + head + " and " + dep + ". Number: " + headEntry.getNumber() + " vs " + depEntry.getNumber());
          continue;
        }

        Set<String> commonCases = new HashSet<>(headEntry.getCase());
        commonCases.retainAll(depEntry.getCase());
        if (headEntry.getCase().size() > 0 && depEntry.getCase().size() > 0 && commonCases.size() == 0) {
          LOG.debug("No match on " + head + " and " + dep + ". Case: " + headEntry.getCase() + " vs " + depEntry.getCase());
          continue;
        }

        LOG.debug("Match on " + head + " and " + dep + ". Case: " + headEntry.getCase() + " vs " + depEntry.getCase() + ". Gender: " + headEntry.getGender()
            + " vs " + depEntry.getGender() + ". Number: " + headEntry.getNumber() + " vs " + depEntry.getNumber());
        return true;
      }
    }

    LOG.debug("No match found");
    return false;
  }

  private void incrementInvalid(String key) {
    if (invalid.containsKey(key)) {
      int count = invalid.get(key);
      invalid.put(key, count + 1);
    } else {
      invalid.put(key, 1);
    }
  }

  @Override
  public void onCompleteParse() throws IOException {
    LOG.info("Counts");
    for (String key : invalid.keySet()) {
      LOG.info(key + ": " + invalid.get(key));
    }
  }

  @Override
  public void close() throws IOException {
  }
}
