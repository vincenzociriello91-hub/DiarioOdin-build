// Ponte veloce Claude <-> Odin 2 Portal di Vincenzo.
// Claude (connettore MCP di claude.ai) mette un comando in coda; la Odin (modulo Magisk, ponte.sh) lo prende con
// una richiesta lunga (fino a 50 s), lo esegue da root e rimanda l'uscita. Tutto in un Durable Object.
// Nessun segreto in questo file: qui ci sono solo le impronte sha256 (HASH_ODIN, HASH_MCP in wrangler.toml).
// Il gettone della Odin e il percorso del connettore si ricavano dalla chiave che sta solo sulla Odin e su Drive.

const ATTESA_ODIN = 50000;      // richiesta lunga della Odin
const ATTESA_MAX = 55;          // secondi massimi che una chiamata del connettore aspetta l'esito
const TENUTI = 50;              // comandi tenuti in memoria

async function sha256(t) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(t));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export class Ponte {
  constructor(state) {
    this.state = state;
    this.attesaOdin = [];          // risolutori delle richieste lunghe della Odin
    this.attesaEsito = new Map();  // id -> [risolutori]
  }

  async lavori() { return (await this.state.storage.get("lavori")) || []; }
  async salva(l) { await this.state.storage.put("lavori", l.slice(-TENUTI)); }

  async fetch(req) {
    const u = new URL(req.url);
    const p = u.pathname;
    if (p === "/aggiungi") {
      const { script } = await req.json();
      const l = await this.lavori();
      const n = ((await this.state.storage.get("seq")) || 0) + 1;
      await this.state.storage.put("seq", n);
      l.push({ id: n, script, stato: "coda", creato: Date.now() });
      await this.salva(l);
      this.attesaOdin.splice(0).forEach((r) => r());
      return Response.json({ id: n });
    }
    if (p === "/prossimo") {
      await this.state.storage.put("visto", Date.now());
      const fine = Date.now() + ATTESA_ODIN;
      while (true) {
        const l = await this.lavori();
        const j = l.find((x) => x.stato === "coda");
        if (j) {
          j.stato = "preso"; j.preso = Date.now();
          await this.salva(l);
          return new Response(`${j.id}\n${j.script}`, { headers: { "content-type": "text/plain; charset=utf-8" } });
        }
        const resto = fine - Date.now();
        if (resto <= 0) return new Response(null, { status: 204 });
        await new Promise((r) => { this.attesaOdin.push(r); setTimeout(r, resto); });
      }
    }
    if (p === "/esito") {
      const id = Number(u.searchParams.get("id"));
      const testo = await req.text();
      const l = await this.lavori();
      const j = l.find((x) => x.id === id);
      if (!j) return new Response("sconosciuto", { status: 404 });
      j.stato = "fatto"; j.uscita = u.searchParams.get("uscita"); j.testo = testo; j.finito = Date.now();
      await this.salva(l);
      (this.attesaEsito.get(id) || []).forEach((r) => r());
      this.attesaEsito.delete(id);
      return new Response("ok");
    }
    if (p === "/aspetta") {
      const id = Number(u.searchParams.get("id"));
      const s = Math.min(Number(u.searchParams.get("s")) || 0, ATTESA_MAX);
      const fine = Date.now() + s * 1000;
      while (true) {
        const j = (await this.lavori()).find((x) => x.id === id);
        if (!j) return Response.json({ errore: "comando sconosciuto" });
        const resto = fine - Date.now();
        if (j.stato === "fatto" || resto <= 0) return Response.json(j);
        await new Promise((r) => {
          if (!this.attesaEsito.has(id)) this.attesaEsito.set(id, []);
          this.attesaEsito.get(id).push(r);
          setTimeout(r, resto);
        });
      }
    }
    if (p === "/stato") {
      const l = await this.lavori();
      return Response.json({
        visto: (await this.state.storage.get("visto")) || 0,
        in_coda: l.filter((x) => x.stato === "coda").length,
        in_corso: l.filter((x) => x.stato === "preso").length,
        ultimi: l.slice(-5).map(({ id, stato, uscita, creato }) => ({ id, stato, uscita, creato })),
      });
    }
    return new Response("?", { status: 404 });
  }
}

const STRUMENTI = [
  {
    name: "odin_esegui",
    description: "Esegue un comando shell (mksh) da root sulla AYN Odin 2 Portal di Vincenzo (LineageOS 24, Magisk) " +
      "e restituisce l'uscita. La Odin prende il comando entro pochi secondi se è accesa. Massimo 5 minuti di esecuzione " +
      "sulla Odin; se il risultato non arriva entro 'attesa_secondi' restituisce l'id da controllare con odin_risultato.",
    inputSchema: {
      type: "object",
      properties: {
        comando: { type: "string", description: "Script shell da eseguire come root." },
        attesa_secondi: { type: "integer", description: "Quanto aspettare l'esito (1-55, predefinito 45)." },
      },
      required: ["comando"],
    },
  },
  {
    name: "odin_risultato",
    description: "Esito di un comando già mandato con odin_esegui (per id), aspettando fino ad 'attesa_secondi'.",
    inputSchema: {
      type: "object",
      properties: { id: { type: "integer" }, attesa_secondi: { type: "integer" } },
      required: ["id"],
    },
  },
  {
    name: "odin_stato",
    description: "Dice se la Odin è collegata al ponte (ultimo contatto) e gli ultimi comandi.",
    inputSchema: { type: "object", properties: {} },
  },
];

function testoEsito(j) {
  if (j.errore) return j.errore;
  if (j.stato !== "fatto") return `Comando ${j.id}: ancora ${j.stato === "coda" ? "in coda (la Odin non l'ha ancora preso)" : "in esecuzione"}. Controlla con odin_risultato id=${j.id}.`;
  return `Comando ${j.id} finito (uscita ${j.uscita}, ${((j.finito - j.creato) / 1000).toFixed(1)} s):\n${j.testo}`;
}

async function strumento(nome, arg, ponte) {
  const att = Math.max(1, Math.min(Number(arg.attesa_secondi) || 45, ATTESA_MAX));
  if (nome === "odin_esegui") {
    if (!arg.comando || typeof arg.comando !== "string") return "comando mancante";
    const { id } = await (await ponte.fetch("https://p/aggiungi", { method: "POST", body: JSON.stringify({ script: arg.comando }) })).json();
    return testoEsito(await (await ponte.fetch(`https://p/aspetta?id=${id}&s=${att}`)).json());
  }
  if (nome === "odin_risultato") return testoEsito(await (await ponte.fetch(`https://p/aspetta?id=${Number(arg.id)}&s=${att}`)).json());
  if (nome === "odin_stato") {
    const s = await (await ponte.fetch("https://p/stato")).json();
    const fa = s.visto ? Math.round((Date.now() - s.visto) / 1000) : null;
    return `Odin: ${fa === null ? "mai collegata" : fa <= 70 ? `collegata (ultimo contatto ${fa} s fa)` : `NON collegata (ultimo contatto ${fa} s fa)`}\n` +
      `in coda: ${s.in_coda}, in esecuzione: ${s.in_corso}\nultimi: ${JSON.stringify(s.ultimi)}`;
  }
  throw new Error("strumento sconosciuto");
}

async function mcp(req, ponte) {
  if (req.method !== "POST") return new Response("Solo POST", { status: 405, headers: { allow: "POST" } });
  let corpo;
  try { corpo = await req.json(); } catch { return Response.json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "JSON non valido" } }, { status: 400 }); }
  const uno = async (m) => {
    if (m.id === undefined || m.id === null) return null;  // notifica
    try {
      if (m.method === "initialize")
        return { jsonrpc: "2.0", id: m.id, result: { protocolVersion: m.params?.protocolVersion || "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "odin-ponte", version: "1.0.0" }, instructions: "Comandi da root sulla Odin 2 Portal di Vincenzo. Leggi prima la skill odin-sistema: niente azioni irreversibili senza conferma." } };
      if (m.method === "ping") return { jsonrpc: "2.0", id: m.id, result: {} };
      if (m.method === "tools/list") return { jsonrpc: "2.0", id: m.id, result: { tools: STRUMENTI } };
      if (m.method === "tools/call") {
        const t = await strumento(m.params?.name, m.params?.arguments || {}, ponte);
        return { jsonrpc: "2.0", id: m.id, result: { content: [{ type: "text", text: t }] } };
      }
      return { jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "metodo sconosciuto" } };
    } catch (e) {
      return { jsonrpc: "2.0", id: m.id, error: { code: -32603, message: String(e.message || e) } };
    }
  };
  if (Array.isArray(corpo)) {
    const r = (await Promise.all(corpo.map(uno))).filter(Boolean);
    return r.length ? Response.json(r) : new Response(null, { status: 202 });
  }
  const r = await uno(corpo);
  return r ? Response.json(r) : new Response(null, { status: 202 });
}

export default {
  async fetch(req, env) {
    const u = new URL(req.url);
    const ponte = env.PONTE.get(env.PONTE.idFromName("odin"));
    if (u.pathname === "/") return new Response("odin-ponte");
    if (u.pathname.startsWith("/mcp/")) {
      if ((await sha256(u.pathname.slice(5))) !== env.HASH_MCP) return new Response("no", { status: 404 });
      return mcp(req, ponte);
    }
    if (u.pathname === "/odin/prossimo" || u.pathname === "/odin/esito") {
      const t = (req.headers.get("authorization") || "").replace(/^Bearer /, "");
      if ((await sha256(t)) !== env.HASH_ODIN) return new Response("no", { status: 403 });
      return ponte.fetch(new Request("https://p/" + u.pathname.slice(6) + u.search, req));
    }
    return new Response("no", { status: 404 });
  },
};
