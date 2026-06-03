package com.example.causal.dsl;

import com.example.causal.dsl.exec.IdentificationResult;
import com.example.causal.dsl.exec.InterventionResult;
import com.example.causal.dsl.exec.ModelListResult;
import com.example.causal.dsl.exec.QueryResult;
import com.example.causal.dsl.exec.VariableSetResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CausalDSLTest {

    private CausalDSL dsl;

    // The UserRetentionModel from the design doc:
    //   Age -> Treatment, Age -> Retention30
    //   PriorUsage -> Treatment, PriorUsage -> Retention30
    //   Treatment -> Engagement -> Retention30
    //   Treatment -> Retention30
    private static final String DEFINE_VARIABLES = """
        DEFINE CAUSAL VARIABLE Treatment  FOR UNIT User TYPE binary     ROLE treatment
        DEFINE CAUSAL VARIABLE Retention30 FOR UNIT User TYPE binary    ROLE outcome
        DEFINE CAUSAL VARIABLE Age         FOR UNIT User TYPE continuous ROLE covariate
        DEFINE CAUSAL VARIABLE PriorUsage  FOR UNIT User TYPE continuous ROLE covariate
        DEFINE CAUSAL VARIABLE Engagement  FOR UNIT User TYPE continuous ROLE mediator
        """;

    private static final String DEFINE_DAG = """
        DEFINE CAUSAL DAG UserRetentionModel {
          Age -> Treatment
          Age -> Retention30
          PriorUsage -> Treatment
          PriorUsage -> Retention30
          Treatment -> Engagement
          Treatment -> Retention30
          Engagement -> Retention30
        }
        """;

    @BeforeEach
    void setUp() {
        dsl = new CausalDSL();
        dsl.executeScript(DEFINE_VARIABLES);
        dsl.executeScript(DEFINE_DAG);
    }

    // ─── Structural queries ───────────────────────────────────────────────────

    @Test
    void causesOf() {
        VariableSetResult r = (VariableSetResult) dsl.execute("CAUSES OF Retention30");
        assertEquals(Set.of("Age", "PriorUsage", "Treatment", "Engagement"), r.variables());
    }

    @Test
    void effectsOf() {
        VariableSetResult r = (VariableSetResult) dsl.execute("EFFECTS OF Treatment");
        assertEquals(Set.of("Engagement", "Retention30"), r.variables());
    }

    @Test
    void ancestorsOf() {
        VariableSetResult r = (VariableSetResult) dsl.execute("ANCESTORS OF Retention30");
        assertTrue(r.variables().containsAll(Set.of("Age", "PriorUsage", "Treatment", "Engagement")));
    }

    @Test
    void descendantsOf() {
        VariableSetResult r = (VariableSetResult) dsl.execute("DESCENDANTS OF Age");
        assertTrue(r.variables().containsAll(Set.of("Treatment", "Retention30", "Engagement")));
    }

    @Test
    void mediatorsBetween() {
        VariableSetResult r = (VariableSetResult) dsl.execute("MEDIATORS BETWEEN Treatment AND Retention30");
        assertEquals(Set.of("Engagement"), r.variables());
    }

    @Test
    void confoundersOf() {
        VariableSetResult r = (VariableSetResult) dsl.execute("CONFOUNDERS OF Treatment AND Retention30");
        assertEquals(Set.of("Age", "PriorUsage"), r.variables());
    }

    @Test
    void backdoorPaths() {
        VariableSetResult r = (VariableSetResult) dsl.execute("BACKDOOR PATHS BETWEEN Treatment AND Retention30");
        // Backdoor paths go through Age and PriorUsage (parents of Treatment)
        assertTrue(r.variables().contains("Age"));
        assertTrue(r.variables().contains("PriorUsage"));
    }

    // ─── Identification ───────────────────────────────────────────────────────

    @Test
    void identifyEffect_withConfounders() {
        IdentificationResult r = (IdentificationResult)
            dsl.execute("IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS");
        assertTrue(r.identifiable());
        assertEquals("backdoor adjustment", r.method());
        assertFalse(r.adjustmentSets().isEmpty());
        Set<String> adjustSet = r.adjustmentSets().get(0);
        assertTrue(adjustSet.containsAll(Set.of("Age", "PriorUsage")),
            "Expected Age and PriorUsage in adjustment set, got: " + adjustSet);
    }

    @Test
    void identifyEffect_noConfounding() {
        // Simple chain: X -> Y, no confounders
        CausalDSL clean = new CausalDSL();
        clean.executeScript("DEFINE CAUSAL DAG SimpleModel { X -> Y }");
        IdentificationResult r = (IdentificationResult)
            clean.execute("IDENTIFY EFFECT OF X ON Y");
        assertTrue(r.identifiable());
        assertTrue(r.method().contains("no backdoor"));
    }

    // ─── Block form definition ────────────────────────────────────────────────

    @Test
    void defineModelBlock() {
        CausalDSL m = new CausalDSL();
        m.executeScript("""
            DEFINE MODEL SmokingModel {
              VARIABLE Smoking TYPE binary ROLE treatment
              VARIABLE Cancer  TYPE binary ROLE outcome
              VARIABLE Tar     TYPE continuous ROLE mediator
              DAG {
                Smoking -> Tar
                Tar -> Cancer
                Smoking -> Cancer
              }
            }
            """);
        VariableSetResult mediators = (VariableSetResult)
            m.execute("MEDIATORS BETWEEN Smoking AND Cancer");
        assertEquals(Set.of("Tar"), mediators.variables());
    }

    // ─── OBSERVED AS binding ──────────────────────────────────────────────────

    @Test
    void observedAsBindingIsPreserved() {
        CausalDSL m = new CausalDSL();
        m.execute("""
            DEFINE CAUSAL VARIABLE Treatment FOR UNIT User
            OBSERVED AS "MATCH (u:User)-[r:EXPOSED_TO]->(f:FeatureFlag {name:'new_ui'}) RETURN u AS unit, r.active AS value"
            TYPE binary ROLE treatment
            """);
        var v = m.registry().standaloneVariables().get("Treatment");
        assertNotNull(v);
        assertNotNull(v.binding());
        assertTrue(v.binding().cypherQuery().contains("EXPOSED_TO"));
    }

    // ─── Estimation stub ─────────────────────────────────────────────────────

    @Test
    void estimateEffectValidatesAdjustmentSet() {
        // Conditioning on a descendant of treatment should be flagged
        QueryResult r = dsl.execute("ESTIMATE EFFECT OF Treatment ON Retention30 ADJUST FOR [Engagement]");
        assertTrue(r.toString().contains("ERROR"));
        assertTrue(r.toString().contains("descendants"));
    }

    @Test
    void estimateEffectWithValidAdjustment() {
        QueryResult r = dsl.execute(
            "ESTIMATE EFFECT OF Treatment ON Retention30 ADJUST FOR [Age, PriorUsage] USING aipw");
        assertTrue(r.toString().contains("aipw"));
    }

    // ─── Intervention (the do-operator) ─────────────────────────────────────────

    @Test
    void intervene_effectReachesOutcome() {
        InterventionResult r = (InterventionResult)
            dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        assertTrue(r.affectsTarget(), "do(Treatment) should reach Retention30");
        assertTrue(r.pathway().contains("Engagement"), "pathway should include the mediator; got: " + r.pathway());
        assertEquals("UserRetentionModel_do_Treatment_1", r.derivedModel());
    }

    @Test
    void intervene_cutsIncomingEdgesButKeepsOutgoing() {
        // do(Treatment) makes the derived world the active graph: Treatment's
        // parents are gone, but its effects are untouched.
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        VariableSetResult causes = (VariableSetResult) dsl.execute("CAUSES OF Treatment");
        assertEquals(Set.of(), causes.variables(), "incoming edges to Treatment should be cut");
        VariableSetResult effects = (VariableSetResult) dsl.execute("EFFECTS OF Treatment");
        assertEquals(Set.of("Engagement", "Retention30"), effects.variables(), "outgoing edges survive");
    }

    @Test
    void intervene_doesNotMutateBaseGraph() {
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        dsl.execute("RESET");
        VariableSetResult causes = (VariableSetResult) dsl.execute("CAUSES OF Treatment");
        assertEquals(Set.of("Age", "PriorUsage"), causes.variables(), "base graph must be intact after RESET");
    }

    @Test
    void intervene_effectDoesNotReachUpstream() {
        InterventionResult r = (InterventionResult)
            dsl.execute("INTERVENE ON Retention30=1 RETURN EFFECT ON Treatment");
        assertFalse(r.affectsTarget(), "do(Retention30) cannot affect its upstream Treatment");
        assertTrue(r.pathway().isEmpty());
    }

    @Test
    void intervene_distributionIsStructuralWithNote() {
        InterventionResult r = (InterventionResult)
            dsl.execute("INTERVENE ON Treatment=1 RETURN DISTRIBUTION OF Retention30");
        assertEquals("DISTRIBUTION", r.returnType());
        assertTrue(r.note().contains("parameter layer"), "should flag numeric values as deferred");
    }

    @Test
    void intervene_compareToReportsStructuralContrast() {
        InterventionResult r = (InterventionResult)
            dsl.execute("INTERVENE ON Treatment=1 COMPARE TO Treatment=0 RETURN EFFECT ON Retention30");
        assertNotNull(r.compareNote());
        assertTrue(r.compareNote().contains("identical causal structure"));
    }

    @Test
    void useModel_switchesActiveGraphAndReset() {
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        // Switch back to the base by name, then to the derived world again.
        dsl.execute("USE MODEL UserRetentionModel");
        VariableSetResult base = (VariableSetResult) dsl.execute("CAUSES OF Treatment");
        assertEquals(Set.of("Age", "PriorUsage"), base.variables());

        dsl.execute("USE MODEL UserRetentionModel_do_Treatment_1");
        VariableSetResult derived = (VariableSetResult) dsl.execute("CAUSES OF Treatment");
        assertEquals(Set.of(), derived.variables());
    }

    // ─── Linear-Gaussian SEM (declared coefficients) ────────────────────────────

    private static CausalDSL chainModel() {
        // X --2--> M --3--> Y, all noise 1, intercept 0.  total effect X→Y = 2·3 = 6
        CausalDSL m = new CausalDSL();
        m.executeScript("""
            DEFINE MODEL Chain {
              VARIABLE X MEAN 0 NOISE 1
              VARIABLE M MEAN 0 NOISE 1
              VARIABLE Y MEAN 0 NOISE 1
              DAG { X -> M WEIGHT 2   M -> Y WEIGHT 3 }
            }
            """);
        return m;
    }

    @Test
    void sem_totalEffectIsProductOfPathCoefficients() {
        InterventionResult r = (InterventionResult)
            chainModel().execute("INTERVENE ON X=1 RETURN EFFECT ON Y");
        assertEquals(6.0, r.totalEffect(), 1e-9);
    }

    @Test
    void sem_interventionalExpectationPropagates() {
        // do(X=4): μ(M)=2·4=8, μ(Y)=3·8=24
        InterventionResult r = (InterventionResult)
            chainModel().execute("INTERVENE ON X=4 RETURN EXPECTATION OF Y");
        assertEquals(24.0, r.expectation(), 1e-9);
    }

    @Test
    void sem_distributionGivesMeanAndVariance() {
        // do(X=1): μ(Y)=3·2=6; Var(M)=σ²=1, Var(Y)=3²·1+1=10
        InterventionResult r = (InterventionResult)
            chainModel().execute("INTERVENE ON X=1 RETURN DISTRIBUTION OF Y");
        assertEquals(6.0, r.expectation(), 1e-9);
        assertEquals(10.0, r.variance(), 1e-9);
    }

    @Test
    void sem_compareToGivesNumericContrast() {
        InterventionResult r = (InterventionResult)
            chainModel().execute("INTERVENE ON X=1 COMPARE TO X=0 RETURN EFFECT ON Y");
        assertEquals(6.0, r.compareDelta(), 1e-9);   // totalEffect·(1−0)
    }

    @Test
    void sem_doIsolatesDirectPathInConfoundedFork() {
        // Z--5-->X, Z--7-->Y, X--2-->Y.  do(X) cuts Z→X, so total effect X→Y = 2 (backdoor excluded).
        CausalDSL m = new CausalDSL();
        m.executeScript("""
            DEFINE MODEL Fork {
              VARIABLE Z MEAN 0 NOISE 1
              VARIABLE X MEAN 0 NOISE 1
              VARIABLE Y MEAN 0 NOISE 1
              DAG { Z -> X WEIGHT 5   Z -> Y WEIGHT 7   X -> Y WEIGHT 2 }
            }
            """);
        InterventionResult eff = (InterventionResult) m.execute("INTERVENE ON X=1 RETURN EFFECT ON Y");
        assertEquals(2.0, eff.totalEffect(), 1e-9);
        // do(X=3): μ(Y)=7·μ(Z)+2·3 = 0+6 = 6; Var(Y)=7²·Var(Z)+σ²_Y = 49+1 = 50 (X fixed ⇒ no cross-term)
        InterventionResult dist = (InterventionResult) m.execute("INTERVENE ON X=3 RETURN DISTRIBUTION OF Y");
        assertEquals(6.0, dist.expectation(), 1e-9);
        assertEquals(50.0, dist.variance(), 1e-9);
    }

    @Test
    void sem_unparameterizedModelStaysStructural() {
        // The setUp UserRetentionModel declares no weights ⇒ no numerics, structural note preserved.
        InterventionResult r = (InterventionResult)
            dsl.execute("INTERVENE ON Treatment=1 RETURN DISTRIBUTION OF Retention30");
        assertNull(r.expectation());
        assertNull(r.totalEffect());
        assertTrue(r.note().contains("parameter layer"));
    }

    // ─── SHOW MODELS ─────────────────────────────────────────────────────────

    @Test
    void showModels_listsBaseModelAsActive() {
        ModelListResult r = (ModelListResult) dsl.execute("SHOW MODELS");
        assertEquals(1, r.models().size());
        ModelListResult.Entry base = r.models().get(0);
        assertEquals("UserRetentionModel", base.name());
        assertTrue(base.active());
        assertFalse(base.derived());
        assertEquals("UserRetentionModel", r.activeModel());
    }

    @Test
    void showModels_listsDerivedWorldAfterIntervene() {
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        ModelListResult r = (ModelListResult) dsl.execute("SHOW MODELS");
        assertEquals(2, r.models().size());
        assertEquals("UserRetentionModel_do_Treatment_1", r.activeModel());

        ModelListResult.Entry derived = r.models().stream()
            .filter(e -> e.name().equals("UserRetentionModel_do_Treatment_1")).findFirst().orElseThrow();
        assertTrue(derived.active());
        assertTrue(derived.derived());

        ModelListResult.Entry base = r.models().stream()
            .filter(e -> e.name().equals("UserRetentionModel")).findFirst().orElseThrow();
        assertFalse(base.active());
        assertFalse(base.derived());
    }

    @Test
    void showModels_activeFollowsReset() {
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        dsl.execute("RESET");
        ModelListResult r = (ModelListResult) dsl.execute("SHOW MODELS");
        assertEquals("UserRetentionModel", r.activeModel());   // back to base; derived world still listed
        assertEquals(2, r.models().size());
    }

    // ─── Colliders ───────────────────────────────────────────────────────────

    @Test
    void colliders() {
        // In a V-structure:  X -> Z <- Y  with no other edges
        // Z is a collider between X and Y
        CausalDSL m = new CausalDSL();
        m.executeScript("DEFINE CAUSAL DAG VStructure { X -> Z  Y -> Z }");
        VariableSetResult r = (VariableSetResult) m.execute("COLLIDERS BETWEEN X AND Y");
        assertTrue(r.variables().contains("Z"), "Z should be a collider; got: " + r.variables());
    }
}
