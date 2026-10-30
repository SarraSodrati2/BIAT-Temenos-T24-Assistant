import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { RecentsService } from './recents.service';

describe('RecentsService', () => {
  let service: RecentsService;

  beforeEach(() => {
    localStorage.removeItem('biat_recents:testeur');
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { username: () => 'testeur' } }],
    });
    service = TestBed.inject(RecentsService);
  });

  afterEach(() => localStorage.removeItem('biat_recents:testeur'));

  it('mémorise les consultations, la plus récente en tête, sans doublon', () => {
    service.record('module', 'MD');
    service.record('table', 'MD.PARAMETER');
    service.record('module', 'MD');
    expect(service.entries().map(e => e.name)).toEqual(['MD', 'MD.PARAMETER']);
    expect(service.modules().length).toBe(1);
    expect(service.tables().length).toBe(1);
  });

  it('persiste par utilisateur et se recharge', () => {
    service.record('table', 'CUSTOMER');
    service.entries();
    service.reload();
    expect(service.entries().map(e => e.name)).toEqual(['CUSTOMER']);
  });

  it('ignore un nom vide et se vide sur demande', () => {
    service.record('module', '');
    expect(service.entries().length).toBe(0);
    service.record('module', 'AC');
    service.clear();
    expect(service.entries().length).toBe(0);
  });
});
