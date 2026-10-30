package backend.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Charge le vrai fichier {@code rag/business-synonyms.properties} (pas de mock) : ces tests
 * vérifient à la fois le mécanisme de chargement/normalisation et le contenu réel du dictionnaire
 * livré avec le projet.
 */
class SynonymDictionaryImplTest {

    private SynonymDictionaryImpl dictionary;

    @BeforeEach
    void setUp() {
        dictionary = new SynonymDictionaryImpl();
        dictionary.load();
    }

    @Test
    void expand_knownAcronym_returnsConfiguredTerms() {
        assertThat(dictionary.expand("KYC")).contains("CUSTOMER");
    }

    @Test
    void expand_isCaseInsensitive() {
        assertThat(dictionary.expand("kyc")).isEqualTo(dictionary.expand("KYC"));
    }

    @Test
    void expand_isAccentInsensitive() {
        assertThat(dictionary.expand("IDENTITE")).isEqualTo(dictionary.expand("identité"));
    }

    @Test
    void expand_unknownTerm_returnsEmpty() {
        assertThat(dictionary.expand("MOT.INCONNU.XYZ")).isEmpty();
    }

    @Test
    void expand_blankTerm_returnsEmpty() {
        assertThat(dictionary.expand("")).isEmpty();
        assertThat(dictionary.expand(null)).isEmpty();
    }

    @Test
    void findMatchingKeys_lowercaseBusinessWordInSentence_isDetected() {
        List<String> matches = dictionary.findMatchingKeys("Quelle table contient les informations du client ?");

        assertThat(matches).contains("CUSTOMER");
    }

    @Test
    void findMatchingKeys_accentedWordInSentence_isDetected() {
        List<String> matches = dictionary.findMatchingKeys("Quelle est la définition de l'identité ?");

        assertThat(matches).isNotEmpty();
    }

    @Test
    void findMatchingKeys_doesNotMatchPartialWords() {
        // "compte" ne doit pas matcher à l'intérieur d'un mot plus long comme "comptes-rendus".
        List<String> matches = dictionary.findMatchingKeys("comptes-rendus");

        // "compte" seul (mot entier) est absent ici, mais un match partiel ne doit pas être compté :
        // on vérifie qu'aucun résultat n'est renvoyé pour une sous-chaîne au milieu d'un autre mot.
        assertThat(matches).isEmpty();
    }

    @Test
    void findMatchingKeys_noBusinessWord_returnsEmpty() {
        assertThat(dictionary.findMatchingKeys("bonjour comment allez vous")).isEmpty();
    }

    @Test
    void findMatchingKeys_pret_expandsToLoanAndLdModule() {
        // "prêt" (langage courant) doit enrichir la recherche vers le module LD (Loans & Deposits).
        List<String> matches = dictionary.findMatchingKeys("C'est quoi la table pret ?");

        assertThat(matches).contains("LD");
    }

    @Test
    void findMatchingKeys_bareTwoLetterModuleCode_isDetectedEvenThoughTooShortForKeywordExtraction() {
        // "ld"/"lc"/"tt" (2 lettres) ne sont jamais extraits comme mot-clé syntaxique par
        // TableKeywordExtractor (qui exige 3 lettres minimum) : c'est ce dictionnaire qui doit les
        // reconnaître et les enrichir vers un terme exploitable par la recherche sémantique.
        assertThat(dictionary.findMatchingKeys("je veux voir le module ld")).isNotEmpty();
        assertThat(dictionary.findMatchingKeys("credit documentaire lc")).contains("CREDIT DOCUMENTAIRE");
        // Valeur attendue corrigée le 2026-10-02 : TT est le module Teller ('teller' figure dans les 20 documents
        // TT de l'index, 'telegraphic transfer' dans aucun) — l'ancienne attente "TRANSFERT" encodait une
        // expansion fausse qui éloignait l'embedding des bons documents. Le but du test (code nu en minuscules
        // reconnu) est inchangé.
        assertThat(dictionary.findMatchingKeys("transfert tt")).contains("TELLER");
    }

    @Test
    void findMatchingKeys_frenchPrepositionDe_isNotTakenForTheDeModuleCode() {
        // Bug réel corrigé (2026-10-02) : "les mouvements de principal" ajoutait les termes du module DE à la
        // requête d'embedding de toute question française contenant "de".
        assertThat(dictionary.findMatchingKeys("Où sont enregistrés les mouvements de principal d'une opération ?"))
                .doesNotContain("DELIVERY");
        assertThat(dictionary.findMatchingKeys("À quoi sert le module DE ?")).contains("DELIVERY");
    }

    @Test
    void expand_creditDocumentaire_resolvesToLc() {
        assertThat(dictionary.expand("credit documentaire")).contains("LC");
    }

    @Test
    void expand_compte_alsoResolvesToAc() {
        assertThat(dictionary.expand("compte")).contains("ACCOUNT", "AC");
    }

    @Test
    void expand_devise_resolvesToCurrency() {
        assertThat(dictionary.expand("devise")).contains("CURRENCY");
    }

    @Test
    void expand_englishBankingAbbreviations_resolveToTheirFullTerm() {
        // Abreviations anglaises courantes (voir ChatbotServiceImpl.buildPrompt) : "cust", "acct",
        // "acc", "bal", "amt", "fx" doivent enrichir la recherche vers leur terme complet.
        assertThat(dictionary.expand("cust")).contains("CUSTOMER");
        assertThat(dictionary.expand("acct")).contains("ACCOUNT");
        assertThat(dictionary.expand("acc")).contains("ACCOUNT");
        assertThat(dictionary.expand("bal")).contains("BALANCE");
        assertThat(dictionary.expand("amt")).contains("AMOUNT");
        assertThat(dictionary.expand("fx")).contains("FOREIGN EXCHANGE");
    }

    @Test
    void expand_depot_resolvesToLdModule() {
        assertThat(dictionary.expand("depot")).contains("LD");
    }

    @Test
    void findMatchingKeys_tt_expandsToTeller_neverToTelegraphicTransfer() {
        // Renommé/corrigé le 2026-10-02 (voir findMatchingKeys_bareTwoLetterModuleCode...) : l'expansion du
        // code module doit utiliser le terme réellement présent dans sa documentation.
        assertThat(dictionary.findMatchingKeys("lc amount")).contains("LETTER OF CREDIT");
        assertThat(dictionary.findMatchingKeys("ou est stocke tt")).contains("TELLER").doesNotContain("TELEGRAPHIC TRANSFER");
    }
}
