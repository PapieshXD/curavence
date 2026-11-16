import { explainText } from './reading-guide';
import { ReadingGuideData } from '../models/reading-guide';

describe('explainText', () => {
  const guide: ReadingGuideData = {enabled:true,documentId:'doc',sourceVersion:'v',dictionaryVersion:'v',
    blocks:[{id:'b',text:'Odstęp PR',occurrences:[{start:0,end:9,termId:'pr'}]}],
    terms:[{id:'pr',name:'Odstęp PR',aliases:['PR'],contextualAliases:['PR'],category:'ecg',definition:'test',sources:[],reviewStatus:'APPROVED',reviewedAt:null,reviewedBy:null}]};
  it('does not use definitions from another document', () => {
    expect(explainText('PR',guide,'another')[0].occurrences).toEqual([]);
  });
  it('does not expand a contextual abbreviation not explicitly matched by the server', () => {
    expect(explainText('PR',guide,'doc')[0].occurrences).toEqual([]);
    expect(explainText('Odstęp PR i Odstęp PR',guide,'doc')[0].occurrences).toHaveLength(1);
  });
});
