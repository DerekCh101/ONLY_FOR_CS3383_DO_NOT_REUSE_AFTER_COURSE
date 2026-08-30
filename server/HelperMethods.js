export function validateTask(body){
    const id = (body.id ||"").trim();
    const title = (body.title || "").trim();
    const duration = (body.duration ||"").trim();
    const state = (body.state || "").trim();
    const placedDate = (body.placed_date || "").trim();
    const placedTime = (body.placed_time || "").trim();
    const deadlineDate = (body.deadline_date || "").trim();
    const deadlineTime = (body.deadline_time || "").trim();
    const lifecycle = (body.lifecycle || "").trim();
    const workflow = (body.workflow || "").trim();
    const placementUpdatedAt = (body.placementUpdatedAt || "").trim();
    const lastUpdatedAt = (body.lastUpdatedAt || "").trim();
    const userId = (body.userId || "").trim();
    if(!id){
        return "id required.";
    }
    if (!title) {
        return "title required";
    }
    if(!state){
        return "state required";
    }
    if(!state){

    }



}

