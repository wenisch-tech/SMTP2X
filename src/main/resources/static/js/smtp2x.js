const csrf=()=>({[document.querySelector('meta[name="_csrf"]')?.content?'X-CSRF-TOKEN':'x-unused']:document.querySelector('meta[name="_csrf"]')?.content||''});
async function api(path,options={}){const r=await fetch('/api/v1'+path,{headers:{'Content-Type':'application/json',...csrf(),...(options.headers||{})},...options});if(!r.ok)throw new Error((await r.json().catch(()=>({error:r.statusText}))).error);return r.status===204?null:r.json()}
async function rows(path,target,columns){const values=await api(path);document.querySelector(target).innerHTML=values.map(v=>'<tr>'+columns.map(c=>'<td>'+String(c(v)??'').replaceAll('<','&lt;')+'</td>').join('')+'</tr>').join('')}
window.smtp2x={api,rows};
