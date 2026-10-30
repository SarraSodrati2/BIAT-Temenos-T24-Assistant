/**
 * Rendu HTML sûr des réponses de l'assistant (utilisé par la conversation ET par l'historique).
 *
 * Sous-ensemble Markdown produit par {@code ChatbotServiceImpl} : titres **en gras**, `code inline`,
 * bloc de citation "> ...", listes à puces (imbriquées), séparateurs "---", blocs de code ``` et
 * tableaux "| a | b |". Pas de dépendance Markdown complète (le format renvoyé par le backend est un
 * sous-ensemble volontairement restreint et connu) ; tout le texte est échappé AVANT d'appliquer nos
 * propres balises, donc aucun contenu (documentation T24, réponse du modèle) ne peut injecter de HTML
 * arbitraire. Aucune donnée T24 n'est inspectée, traduite ni reformulée ici.
 */

/** Une ligne entièrement en gras (ex. "**Fonction**", "**Extrait documentaire**") est un titre de
 * section à part entière ; "**Champ :** `DEAL.SUB.TYP`" n'est PAS entièrement en gras (code inline
 * après la partie grasse) et reste donc une ligne normale — voir {@link formatAnswer}. */
const HEADING_ONLY_LINE = /^\*\*(.+?)\*\*$/;
/** Ligne "badge" d'identité (Champ/Table/Module/Source), toujours "**Libellé :** `valeur`" sur une
 * seule ligne — jamais un titre à part entière (voir HEADING_ONLY_LINE) puisqu'il y a du texte après
 * la partie grasse. "Source / Extrait documentaire" est la variante combinée utilisée par les fiches
 * table/module (voir DocumentOverviewBuilder) : la ligne de citation qui suit reste indépendante (tout
 * bloc "> ..." devient un extrait repliable quelle que soit la section courante, voir flushQuote). */
const CODE_BADGE_LINE = /^\*\*(?:Champ|Table|Module|Source(?:\s*\/\s*Extrait documentaire)?)\s*:?\*\*/i;
const BLOCKQUOTE_PREFIX = /^>\s?/;
const INLINE_CODE = /`([^`]+)`/g;
const INLINE_BOLD = /\*\*([^*]+)\*\*/g;
/** Puce ("- ", "* ", "• ") ou numéro ("1. ", "2) ") ; le groupe 1 (indentation) sert à l'imbrication. */
const LIST_ITEM = /^(\s*)(?:[-*•]|\d+[.)])\s+(.*)$/;
const HORIZONTAL_RULE = /^(?:-{3,}|\*{3,})$/;
const TABLE_ROW = /^\|.*\|$/;
const TABLE_SEPARATOR = /^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$/;
/** Nom qui ressemble à une table T24 (lettres/chiffres/points) : la référence « Source » devient cliquable. */
const TABLE_REFERENCE = /^[A-Za-z][A-Za-z0-9_.$#-]*$/;

/** Section courante déduite du dernier titre rencontré, pour donner à chaque partie sa propre
 * hiérarchie visuelle. Repose uniquement sur les libellés de section définis par
 * {@code ChatbotServiceImpl.SYSTEM_PROMPT}/{@code DocumentOverviewBuilder} (format). */
type MessageSection = 'fonction' | 'exemple' | 'source' | 'extrait' | null;

const SECTION_BY_HEADING: Record<string, MessageSection> = {
  fonction: 'fonction',
  function: 'fonction',
  // "Rôle" (fiches table/module) et "Informations principales" (fiche table) : même style de prose
  // que Fonction, jamais une énumération séparée.
  rôle: 'fonction',
  role: 'fonction',
  'informations principales': 'fonction',
  'key information': 'fonction',
  exemple: 'exemple',
  example: 'exemple',
  source: 'source',
  'extrait documentaire': 'extrait',
  'documentary excerpt': 'extrait',
  // "Attributs" (type/format/règles de validation/valeurs autorisées) : section optionnelle de prose
  // au même titre que Fonction, réutilise son style.
  attributs: 'fonction',
  attributes: 'fonction',
};

/** Au-delà de cette longueur, l'Extrait documentaire est replié par défaut plutôt qu'affiché en entier. */
const QUOTE_TRUNCATE_THRESHOLD = 200;
/** Fin de phrase = ponctuation suivie d'un espace puis d'une majuscule ou d'un guillemet — jamais un
 * simple ".", car les identifiants T24 en contiennent (ex. "MD.DEAL") sans espace après le point :
 * cette exigence d'espace après la ponctuation les exclut naturellement d'une coupure erronée. */
const SENTENCE_END = /[.!?]\s+(?=["A-Z])/g;

interface ListLine {
  indent: number;
  text: string;
}

export function formatAnswer(content: string): string {
  const parts: string[] = [];
  let quoteLines: string[] = [];
  let paragraphLines: string[] = [];
  let listLines: ListLine[] = [];
  let tableLines: string[] = [];
  let codeLines: string[] | null = null;
  // Section du dernier titre rencontré (voir SECTION_BY_HEADING) : donne à chaque paragraphe/bloc
  // sa classe de hiérarchie visuelle (Fonction la plus lisible, Exemple en bloc clair, Source en
  // badge, Extrait documentaire en retrait) sans changer l'algorithme d'analyse lui-même.
  let currentSection: MessageSection = null;

  // L'Extrait documentaire ne doit pas afficher toute une longue description XML dans l'interface :
  // au-delà de QUOTE_TRUNCATE_THRESHOLD, seule la première phrase reste visible directement, le
  // reste repliable via <details>/<summary> natif (rien n'est perdu, juste replié par défaut).
  const flushQuote = () => {
    if (quoteLines.length === 0) {
      return;
    }
    const rawQuote = quoteLines.join(' ');
    quoteLines = [];

    if (rawQuote.length <= QUOTE_TRUNCATE_THRESHOLD) {
      parts.push(`<blockquote class="msg-quote">${formatInline(rawQuote)}</blockquote>`);
      return;
    }

    // Ni lead ni rest ne sont "trim()"-és : concaténés, ils doivent reconstituer rawQuote au
    // caractère près (espace compris entre les deux), pour ne jamais rien perdre ni recoller deux
    // mots l'un contre l'autre.
    const splitAt = firstSentenceEnd(rawQuote);
    const lead = rawQuote.slice(0, splitAt);
    const rest = rawQuote.slice(splitAt);
    const more = rest.trim().length > 0
      ? `<details class="msg-quote__more"><summary>Afficher l'extrait complet</summary>` +
        `<span>${formatInline(rest)}</span></details>`
      : '';
    parts.push(
      `<blockquote class="msg-quote"><span class="msg-quote__lead">${formatInline(lead)}</span>${more}</blockquote>`,
    );
  };
  // Deux lignes brutes consécutives sans ligne vide entre elles sont un seul paragraphe (un simple
  // retour à la ligne dans la réponse du modèle, pas une nouvelle section) : les fusionner évite un
  // paragraphe coupé en deux blocs verticalement espacés au milieu d'une même phrase.
  const flushParagraph = (isBadgeLine: boolean) => {
    if (paragraphLines.length > 0) {
      const sectionClass = isBadgeLine ? ' msg-champ' : currentSection ? ` msg-${currentSection}` : '';
      let html = paragraphLines.join(' ');
      // Section « Source » (titre autonome, pas une ligne badge) : un nom de table devient une
      // référence cliquable vers le référentiel.
      if (currentSection === 'source' && !isBadgeLine) {
        html = html.replace(/<code>([^<]+)<\/code>/g, (match, name: string) =>
          TABLE_REFERENCE.test(name.trim())
            ? `<code class="msg-ref" role="link" tabindex="0" title="Ouvrir ${name.trim()} dans le référentiel">${name}</code>`
            : match,
        );
      }
      parts.push(`<p class="msg-line${sectionClass}">${html}</p>`);
      paragraphLines = [];
    }
  };
  const flushList = () => {
    if (listLines.length > 0) {
      parts.push(renderList(listLines));
      listLines = [];
    }
  };
  const flushTable = () => {
    if (tableLines.length > 0) {
      parts.push(renderTable(tableLines));
      tableLines = [];
    }
  };
  const flushBlocks = () => {
    flushParagraph(false);
    flushQuote();
    flushList();
    flushTable();
  };

  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim();

    // Bloc de code : tout est repris tel quel (échappé), jusqu'à la clôture ```.
    if (codeLines !== null) {
      if (line.startsWith('```')) {
        parts.push(`<pre class="msg-code"><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
        codeLines = null;
      } else {
        codeLines.push(rawLine);
      }
      continue;
    }
    if (line.startsWith('```')) {
      flushBlocks();
      codeLines = [];
      continue;
    }

    if (line.startsWith('>')) {
      flushParagraph(false);
      flushList();
      flushTable();
      quoteLines.push(line.replace(BLOCKQUOTE_PREFIX, ''));
      continue;
    }
    flushQuote();

    if (line === '') {
      flushParagraph(false);
      flushList();
      flushTable();
      continue;
    }

    if (TABLE_ROW.test(line)) {
      flushParagraph(false);
      flushList();
      tableLines.push(line);
      continue;
    }
    flushTable();

    if (HORIZONTAL_RULE.test(line)) {
      flushParagraph(false);
      flushList();
      parts.push('<hr class="msg-rule">');
      continue;
    }

    const listItem = rawLine.match(LIST_ITEM);
    if (listItem) {
      flushParagraph(false);
      listLines.push({ indent: listItem[1].length, text: listItem[2] });
      continue;
    }
    flushList();

    const heading = line.match(HEADING_ONLY_LINE);
    if (heading) {
      flushParagraph(false);
      // Le libellé peut être écrit avec ou sans ":" final ("**Fonction :**" ou "**Fonction**") —
      // le ":" est ignoré pour la correspondance de section (voir SECTION_BY_HEADING) mais reste
      // affiché tel quel : seule sa présence dans le texte source décide, jamais son style.
      const label = heading[1].trim().replace(/\s*:$/, '').toLowerCase();
      currentSection = SECTION_BY_HEADING[label] ?? null;
      const headingClass = currentSection ? ` msg-heading--${currentSection}` : '';
      parts.push(`<div class="msg-heading${headingClass}">${escapeHtml(heading[1])}</div>`);
      continue;
    }

    // "**Champ :** `NOM`" (ou Table/Module/Source) n'est jamais une ligne de titre seule (voir
    // HEADING_ONLY_LINE) mais reste sa propre section visuelle (badge), qu'elle apparaisse ou non
    // avant le premier vrai titre.
    if (paragraphLines.length === 0 && CODE_BADGE_LINE.test(line)) {
      paragraphLines.push(formatInline(line));
      flushParagraph(true);
      continue;
    }

    paragraphLines.push(formatInline(line));
  }
  if (codeLines !== null) {
    parts.push(`<pre class="msg-code"><code>${escapeHtml((codeLines as string[]).join('\n'))}</code></pre>`);
  }
  flushBlocks();

  return parts.join('');
}

/** Liste (imbriquée selon l'indentation d'origine) : chaque élément est échappé puis mis en forme. */
function renderList(items: ListLine[]): string {
  let html = '';
  const stack: number[] = [];
  for (const item of items) {
    if (stack.length === 0) {
      html += '<ul class="msg-list"><li>';
      stack.push(item.indent);
    } else if (item.indent > stack[stack.length - 1]) {
      html += '<ul class="msg-list msg-list--nested"><li>';
      stack.push(item.indent);
    } else {
      while (stack.length > 1 && item.indent < stack[stack.length - 1]) {
        html += '</li></ul>';
        stack.pop();
      }
      html += '</li><li>';
    }
    html += formatInline(item.text);
  }
  while (stack.length > 0) {
    html += '</li></ul>';
    stack.pop();
  }
  return html;
}

/** Tableau Markdown « | a | b | » (avec ou sans ligne de séparation d'en-tête). */
function renderTable(rows: string[]): string {
  const cells = (row: string) => row.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
  const hasHeader = rows.length > 1 && TABLE_SEPARATOR.test(rows[1]);
  const head = hasHeader ? cells(rows[0]) : null;
  const body = rows.slice(hasHeader ? 2 : 0).map(cells);
  const th = head ? `<thead><tr>${head.map(c => `<th>${formatInline(c)}</th>`).join('')}</tr></thead>` : '';
  const tb = `<tbody>${body.map(r => `<tr>${r.map(c => `<td>${formatInline(c)}</td>`).join('')}</tr>`).join('')}</tbody>`;
  return `<div class="msg-table-wrap"><table class="msg-table">${th}${tb}</table></div>`;
}

/** Index (exclusif) de la fin de la première phrase de `text` au sens de {@link SENTENCE_END}, ou
 * `text.length` si aucune frontière de phrase fiable n'est trouvée (aucune coupure n'est alors
 * appliquée — jamais de coupure hasardeuse plutôt qu'une coupure au milieu d'une phrase). */
function firstSentenceEnd(text: string): number {
  SENTENCE_END.lastIndex = 0;
  const match = SENTENCE_END.exec(text);
  return match ? match.index + 1 : text.length;
}

function formatInline(rawLine: string): string {
  return escapeHtml(rawLine)
    .replace(INLINE_CODE, '<code>$1</code>')
    .replace(INLINE_BOLD, '<strong>$1</strong>');
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const CACHE_LIMIT = 200;
const cache = new Map<string, string>();

/**
 * Même résultat que {@link formatAnswer}, mémorisé : les gabarits appellent la mise en forme à chaque
 * cycle de détection de changements, ce qui serait très coûteux pour un listage de plusieurs milliers
 * de lignes (ex. « toutes les informations d'un module »).
 */
export function formatAnswerCached(content: string): string {
  const hit = cache.get(content);
  if (hit !== undefined) {
    return hit;
  }
  const html = formatAnswer(content);
  if (cache.size >= CACHE_LIMIT) {
    cache.delete(cache.keys().next().value as string);
  }
  cache.set(content, html);
  return html;
}
