package backend.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptGlossaryImplTest {

    private ConceptGlossaryImpl glossary;

    @BeforeEach
    void setUp() {
        glossary = new ConceptGlossaryImpl();
        glossary.load(); // vrai fichier rag/fr-en-glossary.properties
    }

    @Test
    void englishTermsFor_frenchConceptualQuestion_returnsTheDocumentationTerms() {
        // Cas réel TT (2026-09-30) : sans ces termes, TELLER.ID n'était que 9ᵉ dans son module.
        assertThat(glossary.englishTermsFor("Comment ouvrir, attribuer et clôturer une caisse ?"))
                .contains("open", "assign", "close", "till");
        // Cas réel DE : DE.FORM.TYPE n'était que 40ᵉ.
        assertThat(glossary.englishTermsFor("Comment définir la largeur et la profondeur d'un formulaire imprimé ?"))
                .contains("width", "depth", "form", "print");
    }

    @Test
    void englishTermsFor_recognisesCommonFrenchInflections() {
        // Pluriels, participes, formes conjuguées : "historiques", "supprime", "enregistrées", "sous-comptes".
        assertThat(glossary.englishTermsFor("Pourquoi supprime-t-on les vieilles données historiques ?"))
                .contains("delete", "purge", "history");
        assertThat(glossary.englishTermsFor("Où sont enregistrées les erreurs ?")).contains("record", "error");
        assertThat(glossary.englishTermsFor("Comment créer des sous-comptes ?")).contains("create", "sub account");
    }

    @Test
    void englishTermsFor_questionWithoutKnownConcept_returnsNothing() {
        assertThat(glossary.englishTermsFor("bonjour, comment allez-vous ?")).isEmpty();
    }

    @Test
    void calibrate_dropsTermsAbsentFromTheIndex_andMarksRareConceptsAsSpecific() {
        Map<String, Integer> frequencies = Map.of("width", 3, "depth", 4, "define", 2000, "form", 400);
        glossary.calibrate(term -> frequencies.getOrDefault(term, 0), 6540);

        // "largeur" -> width (rare) : spécifique ; "définir" -> define (très courant) : jamais vérifié.
        assertThat(glossary.specificConceptsIn("Comment définir la largeur ?")).containsOnlyKeys("LARGEUR");
        // Termes absents de l'index retirés : "caisse" (till, cash absents ici) ne produit plus rien.
        assertThat(glossary.englishTermsFor("caisse")).isEmpty();
        assertThat(glossary.englishTermsFor("largeur")).containsExactly("width");
    }

    @Test
    void specificConceptsIn_questionFormWords_areNeverCheckedAsClaims_evenWhenRare() {
        // Bug réel (2026-10-02) : « Le rôle de ACCOUNT est... » rejeté car le texte de ACCOUNT ne dit pas "role".
        glossary.calibrate(term -> 3, 6540); // tous les termes rares
        assertThat(glossary.specificConceptsIn("Quel est le rôle et la fonction de ACCOUNT ?")).doesNotContainKeys("ROLE", "FONCTION");
        assertThat(glossary.englishTermsFor("Quel est le rôle de ACCOUNT ?")).contains("purpose", "role", "overview");
    }

    @Test
    void specificConceptsIn_beforeCalibration_isEmpty_soNoAnswerIsRejectedOnAnUncheckedGlossary() {
        assertThat(glossary.specificConceptsIn("largeur et profondeur")).isEmpty();
    }

    @Test
    void candidateKeys_coverPluralAndVerbForms() {
        assertThat(ConceptGlossaryImpl.candidateKeys("HISTORIQUES")).contains("HISTORIQUE");
        assertThat(ConceptGlossaryImpl.candidateKeys("SUPPRIME")).contains("SUPPRIMER");
        assertThat(Set.copyOf(ConceptGlossaryImpl.candidateKeys("ENREGISTREES"))).contains("ENREGISTRER");
    }
}
