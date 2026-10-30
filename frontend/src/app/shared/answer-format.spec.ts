import { formatAnswer } from './answer-format';

describe('formatAnswer', () => {
  it('rend une liste à puces', () => {
    const html = formatAnswer('- premier\n- second');
    expect(html).toContain('<li');
    expect(html).toContain('premier');
    expect(html).toContain('second');
  });

  it('rend un tableau Markdown', () => {
    const html = formatAnswer('| Champ | Type |\n|---|---|\n| NAME | STRING |');
    expect(html).toContain('<table');
    expect(html).toContain('NAME');
  });

  it('rend un bloc de code sans interpréter son contenu', () => {
    const html = formatAnswer('```\n<b>x</b>\n```');
    expect(html).toContain('msg-code');
    expect(html).not.toContain('<b>x</b>');
  });

  it("échappe le HTML brut d'une réponse", () => {
    expect(formatAnswer('<script>alert(1)</script>')).not.toContain('<script>');
  });

  it('rend "**Champ :** `NOM`" comme une ligne badge, jamais un titre', () => {
    const html = formatAnswer('**Champ :** `DEAL.SUB.TYP`');
    expect(html).toContain('msg-champ');
    expect(html).toContain('<code>DEAL.SUB.TYP</code>');
    expect(html).not.toContain('msg-heading');
  });

  it('rend "**Table :**" et "**Module :**" comme des lignes badge, au même titre que "**Champ :**"', () => {
    expect(formatAnswer('**Table :** `MD.PARAMETER`')).toContain('msg-champ');
    expect(formatAnswer('**Module :** `MD`')).toContain('msg-champ');
  });

  it('rend "**Source / Extrait documentaire :**" comme une ligne badge suivie d\'un extrait repliable', () => {
    const longQuote = 'This field identifies each separate delivery produced by T24 in connection with the miscellaneous deal. '
      + 'It will hold all the identities of any advices produced by the delivery system in connection with the deal, '
      + 'and is a multi-valued field associated with the ADVICE.TYPE field.';
    const html = formatAnswer(`**Source / Extrait documentaire :** \`MD.DEAL\`\n> « ${longQuote} »`);
    expect(html).toContain('msg-champ');
    expect(html).toContain('msg-quote');
    expect(html).toContain("Afficher l'extrait complet");
  });

  it('applique le style "Fonction" quand "**Rôle :**"/"**Informations principales :**" sont un titre autonome '
    + '(variante multi-lignes du modèle, tolérée au même titre que "**Fonction :**")', () => {
    const role = formatAnswer('**Rôle :**\n\nGère les deals hors bilan.');
    const info = formatAnswer('**Informations principales :**\n\nQuelques champs clés.');
    expect(role).toContain('msg-heading--fonction');
    expect(role).toContain('msg-fonction');
    expect(info).toContain('msg-heading--fonction');
  });
});
