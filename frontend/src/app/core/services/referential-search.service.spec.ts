import { matchKind } from './referential-search.service';

describe('matchKind', () => {
  it('distingue exact, préfixe et sous-chaîne sans tenir compte de la casse', () => {
    expect(matchKind('MD.PARAMETER', 'md.parameter')).toBe('exact');
    expect(matchKind('MD.PARAMETER', 'MD.PAR')).toBe('prefix');
    expect(matchKind('MD.PARAMETER', 'param')).toBe('contains');
  });

  it('renvoie null sans correspondance ou pour une requête vide', () => {
    expect(matchKind('CUSTOMER', 'zzz')).toBeNull();
    expect(matchKind('CUSTOMER', '   ')).toBeNull();
  });
});
