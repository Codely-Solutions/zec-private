// Faucet-documented Argon2id work, bounded to two workers / 180 seconds.
const {Worker,isMainThread,parentPort,workerData}=require('node:worker_threads');
const {argon2Sync}=require('node:crypto');
function bits(bytes){let n=0;for(const b of bytes){if(!b)n+=8;else return n+Math.clz32(b)-24;}return n;}
function hash(seed,nonce){return argon2Sync('argon2id',{parallelism:1,tagLength:32,memory:19456,passes:2,
  message:String(nonce),nonce:Buffer.from(seed,'hex')});}
if(!isMainThread){
  const {seed,difficulty,slot,stop,deadline}=workerData,flag=new Int32Array(stop);
  for(let nonce=slot;Date.now()<deadline&&!Atomics.load(flag,0);nonce+=2){
    if(bits(hash(seed,nonce))>=difficulty){parentPort.postMessage({nonce:String(nonce)});break;}
  }
}else{
  let input='';process.stdin.on('data',chunk=>{input+=chunk;if(input.length>4096)process.exit(2);});
  process.stdin.on('end',async()=>{
    let workers=[],timer,stop;
    try{
      const {seed,difficulty}=JSON.parse(input);
      if(!/^[0-9a-f]{32}$/.test(seed)||!Number.isInteger(difficulty)||difficulty<0||difficulty>14)throw new Error('PoW bounds');
      const began=Date.now();stop=new SharedArrayBuffer(4);
      const nonce=await new Promise((resolve,reject)=>{
        timer=setTimeout(()=>reject(new Error('PoW deadline')),180000);
        let remaining=2;
        for(let slot=0;slot<2;slot++){
          const worker=new Worker(__filename,{workerData:{seed,difficulty,slot,stop,deadline:began+180000}});
          workers.push(worker);
          worker.on('message',value=>resolve(value.nonce));worker.on('error',reject);
          worker.on('exit',()=>{if(--remaining===0)reject(new Error('PoW exhausted'));});
        }
      });
      if(!/^\d{1,15}$/.test(nonce)||bits(hash(seed,nonce))<difficulty)throw new Error('Proof verification failed');
      console.log(JSON.stringify({nonce,seconds:(Date.now()-began)/1000,workers:2,difficulty}));
    }catch(error){console.error(error.message);process.exitCode=1;}
    finally{clearTimeout(timer);if(stop)Atomics.store(new Int32Array(stop),0,1);await Promise.all(workers.map(w=>w.terminate()));}
  });
}
