// Personal SC2 audio add-on. Reuses Modkit's canonical sound planner and source reader; never edits the installation.
// pnpm --dir <Modkit-Blizzard>/starcraft2 exec tsx <StarMine>/tools/sc2-export/export-rts-audio.mjs --out <prepared-assets>
import {execFileSync} from 'node:child_process';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
const option = name => {const i=process.argv.indexOf(name);return i<0?undefined:process.argv[i+1]};
const completing=process.argv.includes('--complete');
const project=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const modkit=resolve(option('--modkit')??join(project,'../modkit-extensions-blizzard/starcraft2'));
const out=option('--out');if(!out)throw Error('--out must name a prepared personal asset directory');
const locale=option('--voice-locale')??'enUS';
const {Sc2DesktopContentSources}=await import(pathToFileURL(join(modkit,'dist/modules/game/battlenet/content-sources/local-content-sources.js')));
const {planUnitSounds,planRtsMusic,readUnitSound}=await import(pathToFileURL(join(modkit,'modules/mod/shared/export/unit-sounds.ts')));
const scratch=resolve(option('--work')??'C:/MKTEMP/starmine-rts-sound');mkdirSync(scratch,{recursive:true});
const source=new Sc2DesktopContentSources({installationDirectory:option('--sc2')??'F:/GAMES/Blizzard/StarCraft II',documentsDirectory:scratch,battleNetCacheDirectory:scratch,sc2MapsCacheDirectory:scratch});
const root=join(resolve(out),'assets/starmine');
const manifest=JSON.parse(readFileSync(join(root,'sounds.json'),'utf8'));
const models=JSON.parse(readFileSync(join(project,'docs/sc2-models.json'),'utf8')).models;
const kinds=[...readFileSync(join(project,'src/main/java/dev/starmine/entity/UnitKind.java'),'utf8').matchAll(/^\t([A-Z_]+)\(/gmu)].map(m=>m[1].toLowerCase().replaceAll('_',''));
const known=new Map(models.filter(m=>typeof m.kind==='string'&&m.model===m.kind.toLowerCase().replaceAll('_','')).map(m=>[m.model,m]));
const aliases={zergplatform:'hatchery',terranplatform:'commandcenter',protossplatform:'nexus'};
const groups=new Map();
for(const kind of kinds){
 const row=known.get(kind), mod=row?.mod??'Mods/VoidMulti.SC2Mod';
 if(!groups.has(mod))groups.set(mod,[]);
 groups.get(mod).push({kind,source:aliases[kind]??(row?.unit??kind).toLowerCase()});
}
const receipt=join(scratch,'rts-audio-receipt.json');
const audit=completing?JSON.parse(readFileSync(receipt,'utf8')):{locale,voices:[],music:[],missing:[],sourceVersion:'installed SC2; resolved through Modkit'};
const convert=async(context,event,planned,music=false)=>{
 const names=[];
 for(const [i,sound] of planned.entries()){
  const name=music?`sc2/music/${event.slice('music.rts.'.length)}_${i}`:`sc2/${event.replace('.','/')}_${i}`;
  const target=join(root,'sounds',name+'.ogg');mkdirSync(dirname(target),{recursive:true});
  const result=await readUnitSound(context,sound,locale,message=>console.warn(message),64<<20);
  const raw=join(scratch,'audio'+sound.path.slice(sound.path.lastIndexOf('.')));writeFileSync(raw,result.bytes);
  execFileSync(process.env.FFMPEG??'ffmpeg',['-v','error','-y','-i',raw,'-ac',music?'2':'1','-c:a','libvorbis','-q:a','5',target],{windowsHide:true});
  names.push(music?{name:'starmine:'+name,stream:true}:'starmine:'+name);
  audit[music?'music':'voices'].push({event,sound:sound.sound,path:sound.gamePath,locale:result.locale??null});
 }
 manifest[event]={...(music?{}:{subtitle:'subtitles.starmine.'+event}),sounds:names};
 console.log(event, names.length);
};
// These StarMine actors are portraits, shared constructs or a differently named SC2 family.
// Announcer lines are explicit substitutes for portraits that have no unit What/Yes set.
const special={
 abathur:['AbathurA_GameResumed','AbathurA_GameResumed'],
 horner:['HornerHanAnnouncer_GoodLuckHaveFun','HornerHanAnnouncer_GameResumed'],
 hybrid:['Hybrid_What','Hybrid_Yes'],
 xelnagaconstruct:['XelnagaConstruct_StandWorkStart_A','XelnagaConstruct_StandWorkEnd_A'],
 xelnagasentinel:['XelnagaConstruct_StandWorkStart_A','XelnagaConstruct_StandWorkEnd_A'],
 voidmonolith:['KhaydarinMonolith_What','KhaydarinMonolith_Ready'],
};
async function completeSpecial(context){
 const sounds={};
 for(const [kind,links] of Object.entries(special))for(const [i,line] of ['select','yes'].entries())
  if(!manifest[kind+'.'+line]?.sounds?.length)sounds[kind+'.'+line]=[links[i]];
 if(!Object.keys(sounds).length)return;
 const plan=await planUnitSounds(context,{kinds:[],abilities:[],events:[],sounds,maxFilesPerEvent:6});
 for(const [event,planned] of Object.entries(plan.events))await convert(context,event,planned);
}
try{
 for(const [mod,units] of completing?new Map([['Campaigns/VoidStory.SC2Campaign',[]]]):groups){
  console.log('Opening',mod,units.length,'kinds');
  const context=await source.openModContext({schemaVersion:1,target:{sourceId:'local:casc',path:mod},locales:[locale]});
  try{
   if(!completing){
   console.log('Planning voices',mod);
   const plan=await planUnitSounds(context,{kinds:[...new Set(units.map(u=>u.source))],abilities:[],maxFilesPerEvent:6,events:['select','yes','ambient']});
   for(const unit of units)for(const line of ['select','yes']){
    const event=unit.kind+'.'+line, planned=plan.events[unit.source+'.'+line];
    if(planned?.length){
     // What already exists as ambient in older personal packs; copy those exact files rather than re-encode it.
     const existing=manifest[unit.kind+'.ambient'];
     if(line==='select'&&plan.events[unit.source+'.ambient']?.[0]?.sound===planned[0].sound&&existing?.sounds?.length){
      manifest[event]={subtitle:'subtitles.starmine.'+event,sounds:existing.sounds};
      audit.voices.push({event,alias:unit.kind+'.ambient',sound:planned[0].sound,path:planned[0].gamePath});
     }else await convert(context,event,planned);
    }else audit.missing.push({event,mod,source:unit.source});
   }
   if(mod==='Mods/VoidMulti.SC2Mod'){
    const music=await planRtsMusic(context);
    for(const [event,planned] of Object.entries(music.events))await convert(context,event,planned,true);
    audit.missing.push(...music.missing);
   }
   const missed=units.filter(u=>audit.missing.some(m=>m.event.startsWith(u.kind+'.')));
   if(missed.length){
    const ids=(await context.query({kind:'data.ids',namespace:'Sound'})).ids;
    for(const unit of missed)audit.voices.push({kind:unit.kind,candidates:ids.filter(id=>id.toLowerCase().includes(unit.kind)).slice(0,30)});
   }
   }
   if(mod==='Campaigns/VoidStory.SC2Campaign')await completeSpecial(context);
  }finally{await context.dispose?.();}
 }
 audit.nativeMissing??=audit.missing;
 audit.aliases??=[];
 for(const kind of kinds)for(const line of ['select','yes']){
  const event=kind+'.'+line;
  if(manifest[event]?.sounds?.length)continue;
  // Interceptors answer through their carrier; silent buildings/larvae reuse their own selection sound.
  const from=kind==='interceptor'?'carrier.'+line:line==='yes'?kind+'.select':manifest[kind+'.ambient']?.sounds?.length?kind+'.ambient':kind+'.yes';
  if(manifest[from]?.sounds?.length){
   manifest[event]={subtitle:'subtitles.starmine.'+event,sounds:manifest[from].sounds};
   audit.aliases.push({event,from,reason:kind==='interceptor'?'carrier controls its interceptors':'SC2 has no separate playable selection/acknowledgement'});
  }
 }
 audit.missing=kinds.flatMap(kind=>['select','yes'].map(line=>kind+'.'+line)).concat(['music.rts.terran','music.rts.protoss','music.rts.zerg'])
  .filter(event=>!manifest[event]?.sounds?.length).map(event=>({event,reason:'no resolved sound'}));
 writeFileSync(join(root,'sounds.json'),JSON.stringify(manifest,null,'\t')+'\n');
 writeFileSync(receipt,JSON.stringify(audit,null,2)+'\n');
 console.log('Missing:',JSON.stringify(audit.missing));
 if(audit.missing.length)throw Error('RTS audio is incomplete; see '+receipt);
}finally{await source.dispose?.();}
process.exit(0);
