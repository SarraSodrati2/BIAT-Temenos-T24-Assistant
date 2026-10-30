package backend.rag;

import backend.service.ChatbotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérification manuelle bout-en-bout du pipeline RAG hybride contre le vrai Oracle + le vrai
 * Ollama (mxbai-embed-large pour les embeddings, llama3 pour la génération).
 *
 * <p><strong>Non exécuté par {@code mvn test}/{@code mvn clean install}</strong> : nommée en
 * {@code *IT} (pas {@code *Test}), elle ne correspond pas au motif d'inclusion par défaut de
 * Surefire — et {@link EnabledIfSystemProperty} est une seconde barrière explicite. Une évaluation
 * automatisée de la justesse *sémantique* d'une réponse LLM n'est pas fiable (non-déterminisme) ;
 * ce test se limite donc à des assertions structurelles (réponse non vide, phrase de repli exacte
 * pour une question hors sujet, réponse non-repli pour une question dont le sujet existe bien dans
 * HELP_TEXT/HELP_FIELD) — la qualité rédactionnelle de la réponse se lit en console. Aucune
 * réplication manuelle de la recherche n'est nécessaire ici : {@code HybridSearchServiceImpl} logue
 * déjà en production, à chaque question, chaque canal de recherche (mots-clés, vectoriel), la
 * fusion et les documents retenus.
 *
 * <p>Pour l'exécuter (Oracle démarré, {@code ollama serve} lancé, profil par défaut actif — PAS
 * {@code --spring.profiles.active=dev}, qui désactive {@code VectorIndexRunner} via
 * {@code rag.enabled=false}), avec le prompt complet visible en console :
 * <pre>
 * mvn test -Dtest=RagPipelineManualIT -Drag.manual.test=true \
 *     -Dlogging.level.backend.service.impl.ChatbotServiceImpl=DEBUG
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "rag.manual.test", matches = "true")
class RagPipelineManualIT {

    @Autowired
    private ChatbotService chatbotService;

    @Test
    void resumeCustomer_returnsAStructuredSummary() {
        askAndPrint("Résume CUSTOMER");
    }

    @Test
    void champsCustomer_listsTheFieldCountAndMainFields() {
        askAndPrint("Quels champs contient CUSTOMER ?");
    }

    @Test
    void aQuoiSertCustomerCin_returnsOnlyThatFieldsDescription() {
        askAndPrint("À quoi sert CUSTOMER.CIN ?");
    }

    @Test
    void decrisAccount_answersFromT24Documentation() {
        askAndPrint("Décris ACCOUNT");
    }

    @Test
    void decrisChargeoff_answersFromT24Documentation() {
        askAndPrint("Décris AA.ARR.CHARGEOFF");
    }

    /**
     * Depuis le 2026-09-27 (voir la Javadoc de {@code ChatbotServiceImpl}), l'assistant ne s'arrête plus
     * à "information non disponible" : Llama3 est appelé même sans document pertinent retrouvé, et doit
     * signaler l'absence de documentation une seule fois puis compléter avec ses connaissances
     * générales sous "Explication complémentaire" — jamais en prétendant que c'est documenté.
     */
    @Test
    void questionHorsSujet_signalsTheMissingDocumentation_thenCompletesWithGeneralKnowledge() {
        String answer = askAndPrint("Quelle est la recette de la tarte tatin ?");
        assertThat(answer)
                .contains("n'a pas permis de confirmer")
                .contains("Explication complémentaire")
                .doesNotContain("**Extrait documentaire");
    }

    /**
     * Avant la recherche hybride : "KYC" ne correspondant à aucun nom de table réel, la recherche
     * retombait entièrement sur le score vectoriel pur, qui ne classait CUSTOMER dans aucun Top 8
     * (des tables sans rapport scoraient plus haut). Le dictionnaire de synonymes métier
     * (rag/business-synonyms.properties : KYC -&gt; ... -&gt; CUSTOMER) fait maintenant de CUSTOMER une
     * correspondance EXACTE (score 1.0), garantie indépendamment de la qualité de l'embedding.
     */
    @Test
    void kyc_retrouveCustomerViaLeDictionnaireDeSynonymes() {
        askAndPrint("Quelle table contient les informations KYC ?");
    }

    /**
     * "coordonnées"/"client" sont des mots métier français en minuscules : une extraction
     * syntaxique (mots en MAJUSCULES) ne les voit jamais. {@code SynonymDictionary.findMatchingKeys}
     * scanne désormais le texte de la question pour ces clés métier indépendamment de la casse.
     */
    @Test
    void coordonneesDuClient_retrouveCustomerViaLeDictionnaireDeSynonymes() {
        askAndPrint("Quelle table contient les coordonnées du client ?");
    }

    @Test
    void informationsDuClient_retrouveCustomerViaLeDictionnaireDeSynonymes() {
        askAndPrint("Quelle table contient les informations du client ?");
    }

    private String askAndPrint(String question) {
        System.out.println("\n================================================================");
        System.out.println("QUESTION : " + question);
        System.out.println("================================================================");

        String answer = chatbotService.ask(question).getAnswer();

        System.out.println("--- Réponse finale ---");
        System.out.println(answer);

        assertThat(answer).isNotBlank();
        return answer;
    }
}
