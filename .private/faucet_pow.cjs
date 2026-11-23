// Faucet-documented Argon2id work, bounded to two workers and a 600-second maximum.
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
      const {seed,difficulty,budgetSeconds=180}=JSON.parse(input);
      if(typeof seed!=='string'||!/^[0-9a-f]{32}$/.test(seed)||!Number.isInteger(difficulty)||difficulty<0||difficulty>14
        ||!Number.isInteger(budgetSeconds)||budgetSeconds<1||budgetSeconds>600)throw new Error('PoW bounds');
      const began=Date.now(),deadline=began+budgetSeconds*1000;stop=new SharedArrayBuffer(4);
      const nonce=await new Promise((resolve,reject)=>{
        timer=setTimeout(()=>reject(new Error('PoW deadline')),budgetSeconds*1000);
        let remaining=2;
        for(let slot=0;slot<2;slot++){
          const worker=new Worker(__filename,{workerData:{seed,difficulty,slot,stop,deadline}});
          workers.push(worker);
          worker.on('message',value=>resolve(value.nonce));worker.on('error',reject);
          worker.on('exit',()=>{if(--remaining===0)reject(new Error('PoW exhausted'));});
        }
      });
      if(!/^\d{1,15}$/.test(nonce)||bits(hash(seed,nonce))<difficulty)throw new Error('Proof verification failed');
      console.log(JSON.stringify({nonce,seconds:(Date.now()-began)/1000,workers:2,difficulty}));
    }catch(error){
      const safeMessages=['PoW bounds','PoW deadline','PoW exhausted','Proof verification failed'];
      console.error(safeMessages.includes(error.message)?error.message:'PoW worker failure');process.exitCode=1;
    }
    finally{clearTimeout(timer);if(stop)Atomics.store(new Int32Array(stop),0,1);await Promise.all(workers.map(w=>w.terminate()));}
  });
}
