const BASE_URL = import.meta.env.VITE_API_URL

export async function getClientTransaction(clientId){
    const response = await fetch(`${BASE_URL}/client/${clientId}/transactions`)

    if(!response.ok){
        throw new Error('Failed to retrieve transaction')
    }

    return response.json()
}

export async function getAllTransaction(){
    const response = await fetch(`${BASE_URL}/transactions`)

    if(!response.ok){
        throw new Error('Failed to retrieve transaction')
    }

    return response.json()
}