package backend.dto;

/**
 * Contexte T24 (module/table/champ) choisi via le parcours à bulles de /chatbot, lu depuis
 * l'entité {@code Chat} et transmis à {@link backend.service.RagService#buildContext} pour
 * restreindre la recherche RAG à la table (et éventuellement au champ) sélectionnés.
 * Tous les champs sont nullable : {@code table}/{@code field} ne sont renseignés qu'une fois
 * l'étape correspondante du parcours à bulles confirmée.
 *
 * <p>{@code previousUserQuestion} n'est pas une sélection utilisateur : c'est la dernière question
 * posée dans la même conversation (voir {@code ChatbotServiceImpl.ask}), utilisée uniquement comme
 * repli par {@code RagServiceImpl} quand la question courante ne contient elle-même aucun mot-clé
 * T24 exploitable (ex. une correction familière "c'est un champ pas une table") — on reprend alors
 * le sujet du tour précédent plutôt que d'échouer la recherche. Porté ici plutôt que comme paramètre
 * séparé de {@code buildContext} pour ne pas changer sa signature publique.
 */
public record ChatContext(String module, String table, String field, String previousUserQuestion) {

    public static final ChatContext EMPTY = new ChatContext(null, null, null, null);

    public ChatContext(String module, String table, String field) {
        this(module, table, field, null);
    }

    public ChatContext withPreviousUserQuestion(String previousUserQuestion) {
        return new ChatContext(module, table, field, previousUserQuestion);
    }

    public boolean hasModule() {
        return module != null && !module.isBlank();
    }

    public boolean hasTable() {
        return table != null && !table.isBlank();
    }

    public boolean hasField() {
        return field != null && !field.isBlank();
    }

    public boolean hasPreviousUserQuestion() {
        return previousUserQuestion != null && !previousUserQuestion.isBlank();
    }
}
