import { SseDecoder } from './realtime';

describe('SSE framing', () => {
  it('handles split packets, CRLF, comments and duplicate transport boundaries', () => {
    const decoder = new SseDecoder();
    expect(decoder.push(': heartbeat\r\n\r\nevent: change\r\ndata: {"id":')).toEqual([]);
    expect(decoder.push('12,"type":"share.revoked"}\r\n')).toEqual([]);
    expect(decoder.push('\r\n')).toEqual([{id:12,type:'share.revoked'}]);
    expect(decoder.push(': alive\n\n')).toEqual([]);
  });
  it('supports multiline data and multiple frames in one packet', () => {
    const decoder = new SseDecoder();
    expect(decoder.push('data: {"id":1,\ndata: "type":"study.created"}\n\ndata: {"id":2,"type":"study.updated"}\n\n').map(e=>e.id)).toEqual([1,2]);
  });
  it('bounds incomplete frames', () => {
    expect(()=>new SseDecoder().push('x'.repeat(65537))).toThrow();
  });
});
